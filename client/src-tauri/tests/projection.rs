use rei_client::{
    application::{backoff, Projection, SseParser},
    domain::*,
};
use serde_json::json;

fn projection() -> Projection {
    Projection::new(
        "server",
        "conversation",
        "p",
        ChatReceipt {
            run_id: "r".into(),
            session_id: "s".into(),
            turn_id: "t".into(),
        },
        "hello",
    )
}
fn event(kind: &str, seq: u64, payload: serde_json::Value) -> Vec<u8> {
    format!(
        "event: {kind}\nid: {seq}\ndata: {}\n\n",
        json!({"type":kind,"sequence":seq,"runId":"r","version":1,"payload":payload})
    )
    .into_bytes()
}
fn apply(p: &mut Projection, kind: &str, seq: u64, payload: serde_json::Value) {
    for frame in SseParser::default()
        .push(&event(kind, seq, payload))
        .unwrap()
    {
        p.apply(frame).unwrap();
    }
}
#[test]
fn delta_duplicates_and_global_sequence_gaps() {
    let mut p = projection();
    apply(
        &mut p,
        "message.delta",
        100,
        json!({"messageId":"m","delta":"れい"}),
    );
    apply(
        &mut p,
        "message.delta",
        100,
        json!({"messageId":"m","delta":"れい"}),
    );
    apply(
        &mut p,
        "message.delta",
        203,
        json!({"messageId":"m","delta":"です"}),
    );
    assert_eq!(p.assistant_text(), "れいです");
    assert_eq!(p.last_sequence, Some(203));
}
#[test]
fn parser_handles_split_utf8_crlf_multiline_and_heartbeat() {
    let bytes = event(
        "message.delta",
        7,
        json!({"messageId":"m","delta":"日本語"}),
    );
    let mut parser = SseParser::default();
    let mut p = projection();
    for byte in bytes {
        for frame in parser.push(&[byte]).unwrap() {
            p.apply(frame).unwrap();
        }
    }
    for frame in parser
        .push(b":comment\r\nevent: heartbeat\r\ndata: {}\r\n\r\n")
        .unwrap()
    {
        p.apply(frame).unwrap();
    }
    assert_eq!(p.last_sequence, Some(7));
    assert_eq!(p.assistant_text(), "日本語");
    let frames = parser
        .push(b"event: future\ndata: {\r\ndata: }\r\n\r\n")
        .unwrap();
    assert_eq!(frames[0].data, "{\n}");
}
#[test]
fn terminal_states_are_final() {
    for (kind, status) in [
        ("agent.run.completed", RunStatus::Completed),
        ("agent.run.failed", RunStatus::Failed),
        ("agent.run.cancelled", RunStatus::Cancelled),
    ] {
        let mut p = projection();
        apply(&mut p, kind, 10, json!({"error":{"message":"failed"}}));
        apply(&mut p, "agent.run.started", 11, json!({}));
        assert_eq!(p.status, status);
        assert_eq!(p.stream_state, StreamState::Closed);
    }
}
#[test]
fn unknown_events_advance_cursor_but_malformed_events_do_not() {
    let mut p = projection();
    apply(&mut p, "future.type", 5, json!({}));
    assert_eq!(p.last_sequence, Some(5));
    let frames = SseParser::default()
        .push(b"id: 6\nevent: message.delta\ndata: broken\n\n")
        .unwrap();
    assert!(p.apply(frames.into_iter().next().unwrap()).is_err());
    assert_eq!(p.last_sequence, Some(5));
    let frames = SseParser::default()
        .push(&event("message.delta", 6, json!({"delta":9})))
        .unwrap();
    assert!(p.apply(frames.into_iter().next().unwrap()).is_err());
    assert_eq!(p.last_sequence, Some(5));
}
#[test]
fn tool_lifecycle_and_working_set_use_real_schema() {
    let mut p = projection();
    apply(
        &mut p,
        "tool.started",
        1,
        json!({"toolCallId":"call","toolName":"readFile","argumentsSummary":"a.rs"}),
    );
    apply(
        &mut p,
        "tool.completed",
        2,
        json!({"toolCallId":"call","toolName":"readFile","resultSummary":"read"}),
    );
    assert_eq!(p.tools["call"].status, "COMPLETED");
    apply(
        &mut p,
        "tool.failed",
        3,
        json!({"toolCallId":"call2","toolName":"writeFile","error":{"message":"denied"}}),
    );
    assert_eq!(p.tools["call2"].status, "FAILED");
    apply(
        &mut p,
        "working_set.item.added",
        4,
        json!({"itemId":"i","kind":"file","identifier":"a","path":"/a"}),
    );
    assert_eq!(p.working_set.len(), 1);
    apply(&mut p, "working_set.item.removed", 5, json!({"itemId":"i"}));
    assert!(p.working_set.is_empty());
}
#[test]
fn completed_message_reconciles_delta_without_double_text() {
    let mut p = projection();
    apply(
        &mut p,
        "message.delta",
        1,
        json!({"messageId":"m","delta":"hel"}),
    );
    apply(
        &mut p,
        "message.completed",
        2,
        json!({"messageId":"m","role":"assistant","text":"hello"}),
    );
    assert_eq!(p.assistant_text(), "hello");
}
#[test]
fn retry_is_bounded() {
    assert_eq!(
        (0..8).map(backoff).collect::<Vec<_>>(),
        vec![1, 2, 4, 8, 15, 30, 30, 30]
    );
}
