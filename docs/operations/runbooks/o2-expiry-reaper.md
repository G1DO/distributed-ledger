# O2 expiry reaper

Reserve stores a deadline using PostgreSQL transaction time plus `ttlSec` seconds. Missing/null
TTL and existing NULL deadlines never expire. A due `RESERVED` row can transition to `EXPIRED`
through Commit, Release, an account capacity query, or the scheduled reaper. The transition is
irreversible: it releases reserved capacity and commits one internal operation, one `EXPIRE`
audit entry, and one outbox event with status `EXPIRED` in the same transaction.

## Scheduling and contention

The scheduler is enabled by default and runs on one application node. There is no distributed
scheduler lock. Defaults are a 1,000 ms fixed delay after each completed sweep, at most 100
candidates per sweep, and a 100 ms PostgreSQL lock timeout per background expiry transaction.
See [configuration](../../reference/configuration.md) for properties and environment variables.

Candidate discovery does not lock reservations. Each candidate has its own transaction with
operation claim → reservation lock (`FOR UPDATE SKIP LOCKED`) → capacity lock. The state and
deadline are rechecked under the reservation lock. A locked reservation is skipped; a lock
timeout or other failed item rolls back all its effects for a later sweep. A batch cap and
short transactions limit contention with user commands. A sweep can finish without draining
the backlog; interval plus batch size determine how quickly it catches up.

All deadline decisions use PostgreSQL `now()`, fixed at each transaction's start. A command
that starts before the deadline may finish after it, including after waiting for a lock.
Terminal rows are never changed, regardless of their deadline. Repeated and concurrent sweeps
create no duplicate expiry effect.

## Rollout and inspection

1. Inspect outstanding non-NULL deadlines before enabling expiry:

   ```sql
   SELECT count(*) AS due_reservations, min(expires_at) AS oldest_due
   FROM reservation
   WHERE status = 'RESERVED' AND expires_at IS NOT NULL AND expires_at <= now();
   ```

2. Stop the preceding application, apply the forward-only migrations through V3, and start one
   expiry-capable node. V3 adds a due-reservation index and updates the column comment without
   backfilling deadlines. Avoid overlapping versions: the preceding version ignores deadlines.
3. Inspect application logs for sweep failures or deferred items. Repeat the backlog query to
   observe progress. `GET /v1/query` itself can trigger expiry, so use SQL when inspecting without
   causing lazy transitions.
4. Inspect expiry history through the database:

   ```sql
   SELECT a.operation_id, a.account_id, a.amount,
          b.payload ->> 'reservationId' AS reservation_id,
          b.payload ->> 'status' AS status
   FROM audit_entry a
   JOIN outbox b ON b.payload ->> 'operationId' = a.operation_id::text
   WHERE a.kind = 'EXPIRE';
   ```

Outbox rows remain `dispatched=false`; this service does not relay expiry events to consumers.
No expiry-specific metrics or alerting are implemented.

## Failure and recovery

A failure before transaction commit leaves no partial status, capacity, operation, audit, or
outbox effect. Restart the application or allow the next sweep to retry. An already committed
expiry remains terminal, so restart and re-sweep do not duplicate it. Do not delete operation
or audit rows to retry an expiry.

Commit/Release first roll back the rejected client's operation claim, then complete lazy expiry
in an independent transaction before returning `409`. If that expiry fails, its entire effect
rolls back and a later access or sweep can retry. Original successful responses remain unchanged;
operation lookup is read-only and may still return the earlier `RESERVED` response.

## Disable and rollback

Set `LEDGER_EXPIRY_ENABLED=false` and restart to disable background sweeps. Lazy expiry on
Commit, Release, and account capacity queries remains active. This flag alone does not suspend
all deadline enforcement.

For application rollback, disable the scheduler and deploy the preceding V2-compatible
Release/Transfer version. Keep V3 and existing data; the previous application ignores deadlines
and therefore pauses expiry enforcement. Already expired rows stay `EXPIRED`, released capacity
stays available, and audit/outbox history remains intact. Do not reactivate expired reservations,
edit applied migrations, or remove Flyway history. See [migration ordering](../../development/database-migrations.md).

## Verification

From `service/` with JDK 25 and Docker available:

```bash
./mvnw -Dit.test=LazyExpiryIT,ExpiryReaperIT,ExpirySchedulerIT verify -Pstrict
./mvnw clean verify -Pstrict
```

See [testing](../../development/testing.md) for scheduler profile isolation and the scope of
database concurrency and recovery checks.
