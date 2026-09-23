use std::io::{BufRead, BufReader, Read, Write};
use std::process::{Command, Output, Stdio};
use std::sync::mpsc;
use std::time::Duration;

fn invoke(input: &[u8]) -> Output {
    let mut child = Command::new(env!("CARGO_BIN_EXE_kv-reference"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(input).unwrap();
    child.wait_with_output().unwrap()
}

#[test]
fn replay_is_byte_identical_with_sorted_state_and_fresh_processes() {
    let input = concat!(
        "{\"op\":\"PUT\",\"key\":\"é\",\"value\":\"v\"}\n",
        "{\"op\":\"CAS\",\"key\":\"A\",\"expected\":null,\"value\":\"\"}\n",
        "{\"op\":\"CAS\",\"key\":\"é\",\"expected\":null,\"value\":\"bad\"}\n",
        "{\"op\":\"GET\",\"key\":\"A\"}\n",
        "{\"op\":\"DELETE\",\"key\":\"absent\"}\r\n",
        "invalid\n",
        "{\"op\":\"PUT\",\"key\":\"💡\",\"value\":\"last\"}"
    );
    let expected = concat!(
        "{\"status\":\"ok\",\"previous\":null}\n",
        "{\"status\":\"ok\",\"swapped\":true,\"previous\":null}\n",
        "{\"status\":\"ok\",\"swapped\":false,\"previous\":\"v\"}\n",
        "{\"status\":\"ok\",\"value\":\"\"}\n",
        "{\"status\":\"ok\",\"previous\":null}\n",
        "{\"status\":\"invalid\"}\n",
        "{\"status\":\"ok\",\"previous\":null}\n",
        "{\"state\":{\"A\":\"\",\"é\":\"v\",\"💡\":\"last\"}}\n"
    );
    for _ in 0..2 {
        let output = invoke(input.as_bytes());
        assert!(output.status.success());
        assert_eq!(output.stdout, expected.as_bytes());
        assert!(output.stderr.is_empty());
    }
    assert_eq!(invoke(b"").stdout, b"{\"state\":{}}\n");
}

#[test]
fn response_is_flushed_before_next_request_or_eof() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_kv-reference"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    let mut input = child.stdin.take().unwrap();
    let mut output = BufReader::new(child.stdout.take().unwrap());
    input
        .write_all(b"{\"op\":\"PUT\",\"key\":\"k\",\"value\":\"v\"}\n")
        .unwrap();
    input.flush().unwrap();
    let (sender, receiver) = mpsc::channel();
    let reader = std::thread::spawn(move || {
        let mut line = String::new();
        output.read_line(&mut line).unwrap();
        sender.send((line, output)).unwrap();
    });
    let (line, mut output) = match receiver.recv_timeout(Duration::from_secs(5)) {
        Ok(response) => response,
        Err(error) => {
            child.kill().unwrap();
            child.wait().unwrap();
            panic!("response was not flushed while stdin remained open: {error}");
        }
    };
    reader.join().unwrap();
    assert_eq!(line, "{\"status\":\"ok\",\"previous\":null}\n");
    input
        .write_all(b"{\"op\":\"GET\",\"key\":\"k\"}\n")
        .unwrap();
    drop(input);
    let mut rest = String::new();
    output.read_to_string(&mut rest).unwrap();
    assert!(child.wait().unwrap().success());
    assert_eq!(
        rest,
        "{\"status\":\"ok\",\"value\":\"v\"}\n{\"state\":{\"k\":\"v\"}}\n"
    );
}

#[test]
fn invalid_utf8_exits_nonzero_without_snapshot() {
    let output = invoke(b"{\"op\":\"GET\",\"key\":\"k\"}\n\xff\n");
    assert!(!output.status.success());
    assert_eq!(output.stdout, b"{\"status\":\"ok\",\"value\":null}\n");
    assert!(!output.stderr.is_empty());
}
