#!/usr/bin/env python3
"""O2 lifecycle and PostgreSQL crash drill on an isolated, disposable Compose stack."""

from contextlib import nullcontext
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time
import traceback
import uuid


ROOT = Path(__file__).resolve().parents[1]
RUN = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:8]
ARTIFACTS = ROOT / "service" / "target" / "o2-compose-e2e" / RUN
PROJECT = "ledger-o2-e2e-" + RUN.lower()
ACCOUNT = "11111111-1111-4111-8111-111111111111"
DESTINATION = "22222222-2222-4222-8222-222222222222"
ENV = {key: value for key, value in os.environ.items() if not key.startswith("COMPOSE_")}
# Port zero asks Docker for an unused host port; neither normal development port is touched.
ENV.update(APP_PORT="127.0.0.1:0", POSTGRES_PORT="127.0.0.1:0")
COMPOSE = ["docker", "compose", "--env-file", "/dev/null", "-p", PROJECT,
           "-f", str(ROOT / "docker-compose.yml")]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def command(args, *, timeout=60, check=True, output=None):
    with (ARTIFACTS / "commands.jsonl").open("a") as log:
        log.write(json.dumps({"time": time.time(), "argv": args}) + "\n")
    # Stream named logs to disk so slow builds and interrupted commands remain inspectable.
    with ((ARTIFACTS / output).open("wb") if output else nullcontext(subprocess.PIPE)) as log:
        result = subprocess.run(args, cwd=ROOT, env=ENV, stdout=log,
                                stderr=subprocess.STDOUT, timeout=timeout)
    if output:
        result.stdout = (ARTIFACTS / output).read_bytes()
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args}\n"
                           + result.stdout.decode(errors="replace")[-6000:])
    return result


def compose(*args, **kwargs):
    return command(COMPOSE + list(args), **kwargs)


def sql(statement, output=None):
    result = compose("exec", "-T", "postgres", "psql", "-X", "-U", "ledger", "-d", "ledger",
                     "-v", "ON_ERROR_STOP=1", "-At", "-c", statement, output=output)
    return result.stdout.decode().strip()


def await_condition(description, condition, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.2)
    raise AssertionError("Timed out waiting for " + description)


def snapshot(name):
    # All persisted business rows, including versions and operation/audit/outbox identities.
    tables = ("account", "capacity", "reservation", "operation", "audit_entry", "outbox")
    parts = []
    for table in tables:
        order = "account_id" if table == "capacity" else "id"
        parts.append(f"'{table}', (SELECT coalesce(json_agg(t ORDER BY {order}), '[]') FROM {table} t)")
    data = json.loads(sql("SELECT json_build_object(" + ",".join(parts) + ")"))
    (ARTIFACTS / (name + ".json")).write_text(json.dumps(data, indent=2) + "\n")
    return data


def check_invariants(state):
    require(sum(row["total"] for row in state["capacity"]) == 1200,
            "Transfer changed the sum of capacity totals")
    for row in state["capacity"]:
        require(all(row[field] >= 0 for field in ("total", "reserved", "committed", "available")),
                "Negative capacity counter")
        require(row["available"] == row["total"] - row["reserved"] - row["committed"],
                "Available capacity disagrees with counters")
        for status, counter in (("RESERVED", "reserved"), ("COMMITTED", "committed")):
            expected = sum(r["amount"] for r in state["reservation"]
                           if r["account_id"] == row["account_id"] and r["status"] == status)
            require(row[counter] == expected, f"Reservation sum disagrees with {counter}")
    operations = {row["id"] for row in state["operation"]}
    require(len(state["audit_entry"]) == len(operations) == len(state["outbox"]),
            "Operation/audit/outbox cardinalities disagree")
    for operation in state["operation"]:
        require(operation["status"] == "COMPLETED" and operation["response_body"] is not None,
                "Incomplete durable operation")
        require(sum(a["operation_id"] == operation["id"] for a in state["audit_entry"]) == 1,
                "Operation lacks exactly one audit effect")
        require(sum(o["payload"]["operationId"] == operation["id"] for o in state["outbox"]) == 1,
                "Operation lacks exactly one outbox effect")


def source_manifest():
    paths = [ROOT / name for name in ("Dockerfile", "docker-compose.yml", "service/pom.xml", "service/mvnw")]
    if (ROOT / ".dockerignore").is_file():
        paths.append(ROOT / ".dockerignore")
    for directory in ("service/src", "service/config", "service/.mvn"):
        paths.extend(path for path in (ROOT / directory).rglob("*") if path.is_file())
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(paths)}


class Http:
    def __init__(self, base_url):
        self.base_url = base_url
        self.count = 0

    def start(self, label, path, body=None):
        self.count += 1
        prefix = ARTIFACTS / f"{self.count:02d}-{label}"
        args = ["curl", "--silent", "--show-error", "--connect-timeout", "3", "--max-time", "45",
                "--dump-header", str(prefix) + ".headers", "--output", str(prefix) + ".body",
                "--write-out", "%{http_code}"]
        request = {"url": self.base_url + path, "method": "POST" if body else "GET"}
        if body:
            body_path = Path(str(prefix) + ".request.json")
            body_path.write_text(json.dumps(body, separators=(",", ":")))
            request["idempotencyKey"] = body["idempotencyKey"]
            args += ["-H", "Content-Type: application/json", "-H",
                     "Idempotency-Key: " + body["idempotencyKey"], "--data-binary", "@" + str(body_path)]
        args.append(request["url"])
        Path(str(prefix) + ".request-meta.json").write_text(json.dumps(request, indent=2) + "\n")
        with (ARTIFACTS / "commands.jsonl").open("a") as log:
            log.write(json.dumps({"time": time.time(), "argv": args}) + "\n")
        process = subprocess.Popen(args, cwd=ROOT, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return process, prefix

    def finish(self, pending, expected=None):
        process, prefix = pending
        status, error = process.communicate(timeout=50)
        code = int(status or b"0")
        Path(str(prefix) + ".result.json").write_text(json.dumps(
            {"httpStatus": code, "curlExit": process.returncode,
             "stderr": error.decode(errors="replace")}, indent=2) + "\n")
        if expected is not None:
            require(process.returncode == 0 and code == expected,
                    f"{prefix.name}: expected HTTP {expected}, got {code}; curl={process.returncode}")
        body_file = Path(str(prefix) + ".body")
        return body_file.read_bytes() if body_file.exists() else b""

    def request(self, label, path, body=None, expected=200):
        return self.finish(self.start(label, path, body), expected)


def run():
    started = time.monotonic()
    ARTIFACTS.mkdir(parents=True)
    print(f"O2 Compose project: {PROJECT}\nArtifacts: {ARTIFACTS}", flush=True)
    result = {"status": "FAILED", "project": PROJECT, "artifactDirectory": str(ARTIFACTS)}
    blocker = None
    pending = None
    try:
        metadata = {"utc": RUN, "platform": platform.platform(), "python": sys.version,
                    "project": PROJECT, "appPort": ENV["APP_PORT"], "postgresPort": ENV["POSTGRES_PORT"]}
        for name, args in (("baseCommit", ["git", "rev-parse", "HEAD"]),
                           ("worktree", ["git", "status", "--short"]),
                           ("docker", ["docker", "version"]),
                           ("compose", ["docker", "compose", "version"]),
                           ("curl", ["curl", "--version"])):
            metadata[name] = command(args).stdout.decode().strip()
        (ARTIFACTS / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
        # Capture this driver too: an uncommitted run is evidence of this working tree, not HEAD.
        (ARTIFACTS / "driver.py").write_bytes(Path(__file__).read_bytes())
        command(["git", "diff", "--binary", "HEAD"], output="worktree.patch")
        compose("config", output="compose-config.yml")
        sources = source_manifest()
        (ARTIFACTS / "source-manifest.json").write_text(json.dumps(sources, indent=2) + "\n")
        print("Building and starting isolated app + PostgreSQL 16...", flush=True)
        compose("up", "--build", "-d", timeout=900, output="compose-up.log")
        require(source_manifest() == sources, "Build inputs changed while the image was being built; rerun")
        compose("images", "--format", "json", output="images.json")
        compose("exec", "-T", "app", "java", "-version", output="java-version.txt")

        def http_client():
            address = compose("port", "app", "8080").stdout.decode().strip()
            return Http("http://" + address)

        http = http_client()
        await_condition("HTTP startup", lambda: command(
            ["curl", "--fail", "--silent", "--max-time", "2", http.base_url + "/health"],
            check=False).returncode == 0, timeout=120)
        require(json.loads(http.request("health", "/health"))["status"] == "UP", "Health failed")
        require(json.loads(http.request("ready", "/ready"))["status"] == "UP", "Ready failed")
        durability = json.loads(sql("SELECT json_build_object('version', version(), 'fsync', "
                                    "current_setting('fsync'), 'synchronous_commit', "
                                    "current_setting('synchronous_commit'), 'full_page_writes', "
                                    "current_setting('full_page_writes'))", output="postgres-environment.json"))
        require(durability["version"].startswith("PostgreSQL 16."), "PostgreSQL 16 is required")
        require(all(durability[key] == "on" for key in ("fsync", "synchronous_commit", "full_page_writes")),
                "PostgreSQL durability settings must be enabled")
        sql(f"INSERT INTO account VALUES ('{ACCOUNT}', 'e2e-source'), ('{DESTINATION}', 'e2e-destination'); "
            f"INSERT INTO capacity(account_id,total) VALUES ('{ACCOUNT}',1000), ('{DESTINATION}',200)",
            output="seed.log")

        def reserve(label, amount, ttl=None):
            body = {"accountId": ACCOUNT, "amount": amount, "idempotencyKey": label}
            if ttl is not None:
                body["ttlSec"] = ttl
            first = http.request(label, "/v1/reserve", body, 201)
            require(http.request(label + "-replay", "/v1/reserve", body) == first,
                    "Reserve replay changed bytes")
            return json.loads(first)["reservationId"], body, first

        def terminal(label, operation, reservation):
            body = {"reservationId": reservation, "idempotencyKey": label}
            first = http.request(label, "/v1/" + operation, body)
            require(json.loads(first)["status"] == {"commit": "COMMITTED", "release": "RELEASED"}[operation],
                    "Unexpected terminal response")
            require(http.request(label + "-replay", "/v1/" + operation, body) == first,
                    "Terminal replay changed bytes")
            require(http.request(label + "-lookup", "/v1/operations/" + label) == first,
                    "Terminal lookup changed bytes")
            return body, first

        print("Running Reserve, Commit, Release, Transfer, scheduled Expire and Query...", flush=True)
        committed, _, _ = reserve("lifecycle-reserve-commit", 100)
        terminal("lifecycle-commit", "commit", committed)
        released, _, _ = reserve("lifecycle-reserve-release", 40)
        terminal("lifecycle-release", "release", released)
        transfer = {"fromAccountId": ACCOUNT, "toAccountId": DESTINATION, "amount": 150,
                    "idempotencyKey": "lifecycle-transfer"}
        transferred = http.request("transfer", "/v1/transfer", transfer)
        require(json.loads(transferred)["status"] == "TRANSFERRED", "Transfer response failed")
        require(http.request("transfer-replay", "/v1/transfer", transfer) == transferred,
                "Transfer replay changed bytes")
        require(http.request("transfer-lookup", "/v1/operations/lifecycle-transfer") == transferred,
                "Transfer lookup changed bytes")
        expired, expiry_body, expiry_response = reserve("lifecycle-reserve-expire", 25, 1)
        # SQL polling does not invoke lazy expiry: this proves the real scheduler runs in Compose.
        await_condition("scheduled expiry", lambda: sql(
            f"SELECT status FROM reservation WHERE id='{expired}'") == "EXPIRED", timeout=30)
        require(http.request("expired-reserve-replay", "/v1/reserve", expiry_body) == expiry_response,
                "Expiry rewrote the original response")
        for account, total, committed_amount in ((ACCOUNT, 850, 100), (DESTINATION, 350, 0)):
            observed = json.loads(http.request("query", "/v1/query?accountId=" + account))
            require(observed == {"accountId": account, "total": total, "reserved": 0,
                                 "committed": committed_amount, "available": total - committed_amount},
                    "Lifecycle query counters differ from expected")
        lifecycle = snapshot("lifecycle")
        check_invariants(lifecycle)
        require({row["id"]: row["status"] for row in lifecycle["reservation"]}
                == {committed: "COMMITTED", released: "RELEASED", expired: "EXPIRED"},
                "Persisted lifecycle terminal states differ from the HTTP results")
        require(sum(a["kind"] == "EXPIRE" for a in lifecycle["audit_entry"]) == 1,
                "Expiry lacks exactly one audit effect")

        crash_reservation, _, _ = reserve("crash-reserve", 60)
        before = snapshot("before-crash")
        check_invariants(before)
        blocker_sql = "BEGIN; LOCK TABLE outbox IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(120); ROLLBACK;"
        (ARTIFACTS / "outbox-blocker.sql").write_text(blocker_sql + "\n")
        blocker_log = (ARTIFACTS / "outbox-blocker.log").open("wb")
        blocker = subprocess.Popen(COMPOSE + ["exec", "-T", "postgres", "psql", "-X", "-U", "ledger",
                                   "-d", "ledger", "-v", "ON_ERROR_STOP=1", "-c", blocker_sql],
                                   cwd=ROOT, env=ENV, stdout=blocker_log, stderr=subprocess.STDOUT)
        blocker_log.close()
        await_condition("outbox blocker", lambda: sql(
            "SELECT count(*) FROM pg_locks WHERE relation='outbox'::regclass "
            "AND mode='AccessExclusiveLock' AND granted") == "1")
        commit_body = {"reservationId": crash_reservation, "idempotencyKey": "crash-commit"}
        pending = http.start("interrupted-commit", "/v1/commit", commit_body)
        await_condition("Commit after business and audit writes, waiting on outbox", lambda: sql(
            "SELECT count(*) FROM pg_stat_activity WHERE usename='app_role' "
            "AND wait_event_type='Lock' AND query LIKE 'INSERT INTO outbox%'") == "1")
        sql("SELECT json_agg(t) FROM (SELECT pid,usename,state,wait_event_type,wait_event,query "
            "FROM pg_stat_activity WHERE wait_event_type='Lock') t", output="crash-boundary.json")
        print("Observed Commit waiting at outbox; killing PostgreSQL and recovering WAL...", flush=True)
        compose("kill", "--signal", "SIGKILL", "postgres", output="postgres-kill.log")
        http.finish(pending)  # An interrupted request has an unknown outcome until durable lookup.
        pending = None
        blocker.wait(timeout=15)
        blocker = None
        compose("start", "postgres", output="postgres-restart.log")
        await_condition("PostgreSQL recovery", lambda: compose(
            "exec", "-T", "postgres", "psql", "-X", "-U", "ledger", "-d", "ledger", "-Atc", "SELECT 1",
            check=False).returncode == 0)
        recovered = snapshot("after-crash")
        require(recovered == before, "Crash left partial business, operation, audit or outbox state")
        check_invariants(recovered)
        http.request("rolled-back-lookup", "/v1/operations/crash-commit", expected=404)
        original = http.request("recovered-commit", "/v1/commit", commit_body)
        require(json.loads(original)["status"] == "COMMITTED", "Commit recovery failed")
        acknowledged = snapshot("acknowledged-commit")
        check_invariants(acknowledged)

        # Crash once more after acknowledgment, then restart the app as well. Replay must use WAL data.
        compose("kill", "--signal", "SIGKILL", "postgres", output="acknowledged-postgres-kill.log")
        compose("start", "postgres", output="acknowledged-postgres-restart.log")
        await_condition("acknowledged PostgreSQL recovery", lambda: compose(
            "exec", "-T", "postgres", "psql", "-X", "-U", "ledger", "-d", "ledger", "-Atc", "SELECT 1",
            check=False).returncode == 0)
        compose("restart", "app", output="app-restart.log")
        http.base_url = http_client().base_url
        await_condition("restarted application", lambda: command(
            ["curl", "--fail", "--silent", "--max-time", "2", http.base_url + "/health"],
            check=False).returncode == 0, timeout=120)
        require(http.request("commit-after-restart-replay", "/v1/commit", commit_body) == original,
                "Committed response changed bytes after PostgreSQL/app restart")
        require(http.request("commit-after-restart-lookup", "/v1/operations/crash-commit") == original,
                "Stored lookup changed bytes after PostgreSQL/app restart")
        final = snapshot("final")
        require(final == acknowledged, "Acknowledged rows changed after crash/restart/replay")
        check_invariants(final)
        observed = json.loads(http.request("final-query", "/v1/query?accountId=" + ACCOUNT))
        require(observed == {"accountId": ACCOUNT, "total": 850, "reserved": 0,
                             "committed": 160, "available": 690}, "Final query counters differ")
        result.update(status="PASSED", lifecycle="Reserve/Commit/Release/Transfer/Expire/Query",
                      crashBoundary="Commit blocked at outbox insert after business/audit writes",
                      rollback="all six business tables unchanged", replay="byte-identical after PG/app restart",
                      recordedHttpRequests=http.count, operations=len(final["operation"]))
    except BaseException as error:
        result["error"] = str(error)
        (ARTIFACTS / "failure.txt").write_text(traceback.format_exc())
        raise
    finally:
        if pending and pending[0].poll() is None:
            pending[0].kill()
            pending[0].communicate()
        if blocker and blocker.poll() is None:
            blocker.terminate()
        try:
            compose("logs", "--no-color", "--timestamps", check=False, output="compose.log")
            compose("ps", "-a", "--format", "json", check=False, output="containers.json")
        finally:
            cleanup = compose("down", "--volumes", "--remove-orphans", timeout=90,
                              check=False, output="cleanup.log")
            result["cleanupExit"] = cleanup.returncode
            if cleanup.returncode:
                result.update(status="FAILED", error="Compose cleanup failed; inspect cleanup.log")
            result["durationSeconds"] = round(time.monotonic() - started, 3)
            (ARTIFACTS / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print(f"{result['status']} in {result['durationSeconds']}s; artifacts: {ARTIFACTS}", flush=True)
            require(cleanup.returncode == 0, "Compose cleanup failed; inspect cleanup.log")


if __name__ == "__main__":
    run()
