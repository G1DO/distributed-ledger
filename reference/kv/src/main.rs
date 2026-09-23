use serde::de::{self, MapAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;
use std::fmt;
use std::io::{self, BufRead, Write};
use std::process::ExitCode;

// Parsing directly into Value would silently replace duplicate object members.
struct Members(BTreeMap<String, Value>);

impl<'de> Deserialize<'de> for Members {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct MembersVisitor;

        impl<'de> Visitor<'de> for MembersVisitor {
            type Value = Members;

            fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
                formatter.write_str("an object with unique members")
            }

            fn visit_map<M: MapAccess<'de>>(self, mut map: M) -> Result<Members, M::Error> {
                let mut members = BTreeMap::new();
                while let Some((key, value)) = map.next_entry::<String, Value>()? {
                    if members.insert(key, value).is_some() {
                        return Err(de::Error::custom("duplicate member"));
                    }
                }
                Ok(Members(members))
            }
        }

        deserializer.deserialize_map(MembersVisitor)
    }
}

#[derive(Debug)]
enum Request {
    Get(String),
    Put(String, String),
    Delete(String),
    Cas(String, Option<String>, String),
}

fn take_string(members: &mut BTreeMap<String, Value>, name: &str) -> Option<String> {
    match members.remove(name)? {
        Value::String(value) => Some(value),
        _ => None,
    }
}

fn parse_request(line: &str) -> Option<Request> {
    let Members(mut members) = serde_json::from_str(line).ok()?;
    let op = take_string(&mut members, "op")?;
    let key = take_string(&mut members, "key")?;
    if key.is_empty() {
        return None;
    }
    let request = match op.as_str() {
        "GET" => Request::Get(key),
        "PUT" => Request::Put(key, take_string(&mut members, "value")?),
        "DELETE" => Request::Delete(key),
        "CAS" => {
            // Missing expected is invalid; explicit null means the key is absent.
            let expected = match members.remove("expected")? {
                Value::Null => None,
                Value::String(value) => Some(value),
                _ => return None,
            };
            Request::Cas(key, expected, take_string(&mut members, "value")?)
        }
        _ => return None,
    };
    members.is_empty().then_some(request)
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(tag = "status")]
enum Response {
    #[serde(rename = "ok")]
    Get { value: Option<String> },
    #[serde(rename = "ok")]
    Write { previous: Option<String> },
    #[serde(rename = "ok")]
    Cas {
        swapped: bool,
        previous: Option<String>,
    },
    #[serde(rename = "invalid")]
    Invalid,
}

#[derive(Default, Serialize)]
struct Store {
    state: BTreeMap<String, String>,
}

impl Store {
    fn apply(&mut self, line: &str) -> Response {
        match parse_request(line) {
            Some(Request::Get(key)) => Response::Get {
                value: self.state.get(&key).cloned(),
            },
            Some(Request::Put(key, value)) => Response::Write {
                previous: self.state.insert(key, value),
            },
            Some(Request::Delete(key)) => Response::Write {
                previous: self.state.remove(&key),
            },
            Some(Request::Cas(key, expected, value)) => {
                let previous = self.state.get(&key).cloned();
                let swapped = previous == expected;
                if swapped {
                    self.state.insert(key, value);
                }
                Response::Cas { swapped, previous }
            }
            None => Response::Invalid,
        }
    }
}

fn write_line(output: &mut impl Write, value: &impl Serialize) -> io::Result<()> {
    serde_json::to_writer(&mut *output, value).map_err(io::Error::other)?;
    output.write_all(b"\n")?;
    output.flush()
}

fn run(mut input: impl BufRead, mut output: impl Write) -> io::Result<()> {
    let mut store = Store::default();
    let mut line = String::new();
    loop {
        line.clear();
        // read_line rejects invalid UTF-8. Such I/O failures do not produce a snapshot.
        if input.read_line(&mut line)? == 0 {
            return write_line(&mut output, &store);
        }
        write_line(&mut output, &store.apply(&line))?;
    }
}

fn main() -> ExitCode {
    match run(io::stdin().lock(), io::stdout().lock()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("kv-reference: {error}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::io::{BufReader, Cursor, Read};

    #[test]
    fn transitions_distinguish_absent_empty_and_cas_mismatch() {
        let mut store = Store::default();
        let cases = [
            (
                json!({"op":"GET", "key":"k"}),
                json!({"status":"ok", "value":null}),
            ),
            (
                json!({"op":"DELETE", "key":"k"}),
                json!({"status":"ok", "previous":null}),
            ),
            (
                json!({"op":"CAS", "key":"k", "expected":"", "value":"bad"}),
                json!({"status":"ok", "swapped":false, "previous":null}),
            ),
            (
                json!({"op":"CAS", "key":"k", "expected":null, "value":""}),
                json!({"status":"ok", "swapped":true, "previous":null}),
            ),
            (
                json!({"op":"GET", "key":"k"}),
                json!({"status":"ok", "value":""}),
            ),
            (
                json!({"op":"CAS", "key":"k", "expected":null, "value":"bad"}),
                json!({"status":"ok", "swapped":false, "previous":""}),
            ),
            (
                json!({"op":"CAS", "key":"k", "expected":"", "value":"v"}),
                json!({"status":"ok", "swapped":true, "previous":""}),
            ),
            (
                json!({"op":"CAS", "key":"k", "expected":"other", "value":"bad"}),
                json!({"status":"ok", "swapped":false, "previous":"v"}),
            ),
            (
                json!({"op":"PUT", "key":"k", "value":"next"}),
                json!({"status":"ok", "previous":"v"}),
            ),
            (
                json!({"op":"PUT", "key":"other", "value":"untouched"}),
                json!({"status":"ok", "previous":null}),
            ),
            (
                json!({"op":"DELETE", "key":"k"}),
                json!({"status":"ok", "previous":"next"}),
            ),
            (
                json!({"op":"GET", "key":"k"}),
                json!({"status":"ok", "value":null}),
            ),
            (
                json!({"op":"GET", "key":"other"}),
                json!({"status":"ok", "value":"untouched"}),
            ),
        ];
        for (request, expected) in cases {
            assert_eq!(
                serde_json::to_value(store.apply(&request.to_string())).unwrap(),
                expected,
                "request: {request}"
            );
        }
        assert_eq!(
            store.state,
            BTreeMap::from([("other".into(), "untouched".into())])
        );
    }

    #[test]
    fn invalid_requests_never_change_state() {
        let mut store = Store::default();
        store.apply(r#"{"op":"PUT","key":"k","value":"original"}"#);
        let original = store.state.clone();
        for line in [
            "",
            " ",
            "null",
            "[]",
            "true",
            "42",
            "{}",
            "{",
            "{\"op\":NaN}",
            r#"{"op":"get","key":"k"}"#,
            r#"{"op":"APPEND","key":"k","value":"bad"}"#,
            r#"{"op":"PUT","value":"bad"}"#,
            r#"{"op":"PUT","key":"","value":"bad"}"#,
            r#"{"op":"PUT","key":7,"value":"bad"}"#,
            r#"{"op":"PUT","key":"k"}"#,
            r#"{"op":"PUT","key":"k","value":null}"#,
            r#"{"op":"PUT","key":"k","value":false}"#,
            r#"{"op":"PUT","key":"k","value":{"a":1,"a":2}}"#,
            r#"{"op":"CAS","key":"k","value":"bad"}"#,
            r#"{"op":"CAS","key":"k","expected":5,"value":"bad"}"#,
            r#"{"op":"CAS","key":"k","expected":"original"}"#,
            r#"{"op":"CAS","key":"k","expected":"original","value":null}"#,
            r#"{"op":"PUT","key":"k","value":"bad","extra":1}"#,
            r#"{"op":"GET","key":"k","value":"bad"}"#,
            r#"{"op":"DELETE","key":"k","expected":null}"#,
            r#"{"op":"PUT","op":"PUT","key":"k","value":"bad"}"#,
            r#"{"op":"GET","op":"DELETE","key":"k"}"#,
            r#"{"op":"PUT","key":"k","\u006bey":"k","value":"bad"}"#,
            r#"{"op":"PUT","key":"k","value":"one","value":"two"}"#,
            r#"{"op":"CAS","key":"k","expected":null,"expected":"original","value":"bad"}"#,
            r#"{"op":"PUT","key":"k","value":"\ud800"}"#,
            r#"{"op":"PUT","key":"\udc00","value":"bad"}"#,
            r#"{"op":"DELETE","key":"k"} trailing"#,
            r#"{"op":"DELETE","key":"k"}{"op":"GET","key":"k"}"#,
        ] {
            assert_eq!(store.apply(line), Response::Invalid, "request: {line}");
            assert_eq!(store.state, original, "request: {line}");
        }
    }

    #[test]
    fn unicode_is_preserved_without_normalization() {
        let mut store = Store::default();
        for key in ["é", "e\u{301}", "💡", "\0"] {
            assert_eq!(
                store.apply(&json!({"op":"PUT", "key":key, "value":"\n\0💡"}).to_string()),
                Response::Write { previous: None }
            );
            assert_eq!(
                store.apply(&json!({"op":"GET", "key":key}).to_string()),
                Response::Get {
                    value: Some("\n\0💡".into())
                }
            );
        }
        assert_eq!(store.state.len(), 4);
        assert_eq!(
            store.apply(r#"{"op":"GET","key":"\ud83d\udca1"}"#),
            Response::Get {
                value: Some("\n\0💡".into())
            }
        );
    }

    #[test]
    fn read_failure_does_not_emit_a_success_snapshot() {
        struct FailingReader;
        impl Read for FailingReader {
            fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
                Err(io::Error::other("injected read failure"))
            }
        }
        let input = Cursor::new(b"{\"op\":\"GET\",\"key\":\"k\"}\n").chain(FailingReader);
        let mut output = Vec::new();
        assert!(run(BufReader::new(input), &mut output).is_err());
        assert_eq!(output, b"{\"status\":\"ok\",\"value\":null}\n");
    }

    #[test]
    fn output_failure_is_fatal() {
        struct FailingWriter;
        impl Write for FailingWriter {
            fn write(&mut self, _: &[u8]) -> io::Result<usize> {
                Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "injected write failure",
                ))
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        assert!(
            run(
                Cursor::new(b"{\"op\":\"GET\",\"key\":\"k\"}\n"),
                FailingWriter
            )
            .is_err()
        );
    }
}
