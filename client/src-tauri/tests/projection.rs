use rei_client_lib::{
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

#[test]
fn message_started_and_tool_lifecycle_preserve_identity_and_metadata() {
    let mut p = projection();
    apply(
        &mut p,
        "message.started",
        1,
        json!({"messageId":"m","role":"assistant"}),
    );
    assert_eq!(p.messages.len(), 1);
    assert!(!p.messages[0].completed);
    apply(
        &mut p,
        "message.delta",
        2,
        json!({"messageId":"m","delta":"hi"}),
    );
    apply(
        &mut p,
        "message.completed",
        3,
        json!({"messageId":"m","role":"assistant","text":"hello"}),
    );
    assert!(p.messages[0].completed);
    assert_eq!(p.assistant_text(), "hello");
    apply(
        &mut p,
        "tool.started",
        4,
        json!({"toolCallId":"c","toolName":"read","summary":"safe"}),
    );
    apply(
        &mut p,
        "tool.completed",
        5,
        json!({"toolCallId":"c","toolName":"read","duration":18}),
    );
    assert_eq!(p.tools.len(), 1);
    assert_eq!(p.tools["c"].summary, "safe");
    assert_eq!(p.tools["c"].duration_ms, Some(18));
    apply(
        &mut p,
        "tool.failed",
        6,
        json!({"toolCallId":"d","toolName":"write","error":{"type":"error","message":"denied"}}),
    );
    assert_eq!(p.tools["d"].error.as_ref().unwrap().message, "denied");
}

#[test]
fn live_activity_correlates_llm_and_retains_progress_and_working_set_changes() {
    let mut p = projection();
    apply(
        &mut p,
        "llm.request.started",
        1,
        json!({"requestId":"q","feature":"chat"}),
    );
    apply(
        &mut p,
        "llm.response.first_token",
        2,
        json!({"requestId":"q","durationMs":70}),
    );
    apply(
        &mut p,
        "llm.response.completed",
        3,
        json!({"requestId":"q","durationMs":210}),
    );
    assert_eq!(p.activities.len(), 1);
    let llm = &p.activities[0];
    assert_eq!(llm.status, "COMPLETED");
    assert_eq!(llm.first_token_ms, Some(70));
    assert_eq!(llm.duration_ms, Some(210));
    assert_eq!(llm.summary, "chat");
    apply(
        &mut p,
        "stagnation.updated",
        4,
        json!({"consecutiveNoProgressIterations":1,"threshold":4,"stagnationReplanCount":0,"maxStagnationReplans":2}),
    );
    assert_eq!(p.activities[1].label, "No progress");
    assert_eq!(p.activities[1].metrics[0].value, 1);
    apply(
        &mut p,
        "working_set.item.added",
        5,
        json!({"itemId":"file","kind":"file","identifier":"Foo.java"}),
    );
    apply(
        &mut p,
        "working_set.item.removed",
        6,
        json!({"itemId":"file"}),
    );
    assert!(p.working_set.is_empty());
    assert_eq!(p.activities.last().unwrap().summary, "Foo.java");
    let count = p.activities.len();
    apply(
        &mut p,
        "working_set.item.removed",
        6,
        json!({"itemId":"file"}),
    );
    apply(
        &mut p,
        "working_set.item.added",
        5,
        json!({"itemId":"file"}),
    );
    apply(&mut p, "future.event", 7, json!({}));
    assert_eq!(p.activities.len(), count);
    assert_eq!(p.last_sequence, Some(7));
}

#[test]
fn skill_routing_uses_envelope_correlation_and_lifecycle_timestamps() {
    let mut p = projection();
    for (sequence, kind, payload) in [
        (
            1,
            "skill.routing.started",
            json!({"candidateCount":4,"routingInvocation":1}),
        ),
        (
            2,
            "skill.candidates.evaluated",
            json!({"totalSkillCount":8,"candidateCount":4,"actualSelectedSkill":"coding"}),
        ),
        (
            3,
            "skill.routing.completed",
            json!({"durationMs":22,"selectedSkill":"coding"}),
        ),
    ] {
        let data = json!({"type":kind,"sequence":sequence,"runId":"r","sessionId":"s","turnId":"t","projectId":"p",
            "version":1,"timestamp":format!("2026-09-17T00:00:0{sequence}Z"),"correlationId":"routing","payload":payload});
        let bytes = format!("event: {kind}\nid: {sequence}\ndata: {data}\n\n");
        p.apply(
            SseParser::default()
                .push(bytes.as_bytes())
                .unwrap()
                .remove(0),
        )
        .unwrap();
    }
    assert_eq!(p.activities.len(), 1);
    assert_eq!(p.activities[0].summary, "coding");
    assert_eq!(p.activities[0].duration_ms, Some(22));
    assert_eq!(
        p.activities[0].started_at.as_deref(),
        Some("2026-09-17T00:00:01Z")
    );
    assert_eq!(
        p.activities[0].completed_at.as_deref(),
        Some("2026-09-17T00:00:03Z")
    );
    apply(
        &mut p,
        "skill.selection.started",
        4,
        json!({"selectionId":"selection"}),
    );
    apply(
        &mut p,
        "skill.selection.failed",
        5,
        json!({"selectionId":"selection","error":{"type":"operation_failed","message":"Operation failed."}}),
    );
    assert_eq!(p.activities[1].status, "FAILED");
    apply(
        &mut p,
        "thinking.started",
        6,
        json!({"thinkingId":"thinking"}),
    );
    apply(
        &mut p,
        "thinking.completed",
        7,
        json!({"thinkingId":"thinking"}),
    );
    assert_eq!(p.activities[2].status, "COMPLETED");
}

#[test]
fn wrong_session_envelope_cannot_contaminate_current_run() {
    let mut p = projection();
    let data = json!({"type":"message.delta","sequence":1,"runId":"r","version":1,
        "sessionId":"other","payload":{"messageId":"m","delta":"other session"}});
    let bytes = format!("event: message.delta\nid: 1\ndata: {data}\n\n");
    assert!(p
        .apply(
            SseParser::default()
                .push(bytes.as_bytes())
                .unwrap()
                .remove(0)
        )
        .is_err());
    assert!(p.last_sequence.is_none());
}

#[test]
fn mismatched_id_and_cross_run_frames_do_not_advance_sequence() {
    let mut p = projection();
    for data in [
        json!({"type":"message.delta","sequence":2,"runId":"r","version":1,"payload":{"messageId":"m","delta":"bad"}}),
        json!({"type":"message.delta","sequence":1,"runId":"other","version":1,"payload":{"messageId":"m","delta":"bad"}}),
    ] {
        let bytes = format!("event: message.delta\nid: 1\ndata: {data}\n\n");
        assert!(p
            .apply(
                SseParser::default()
                    .push(bytes.as_bytes())
                    .unwrap()
                    .remove(0)
            )
            .is_err());
        assert!(p.last_sequence.is_none());
        assert!(p.assistant_text().is_empty());
    }
}
#[test]
fn heartbeat_with_id_still_cannot_change_cursor() {
    let mut p = projection();
    apply(
        &mut p,
        "message.delta",
        7,
        json!({"messageId":"m","delta":"ok"}),
    );
    let frame = SseParser::default()
        .push(b"event: heartbeat\nid: 999\ndata: {}\n\n")
        .unwrap()
        .remove(0);
    p.apply(frame).unwrap();
    assert_eq!(p.last_sequence, Some(7));
}
#[test]
fn parser_bounds_unterminated_frames_and_rejects_invalid_utf8() {
    assert!(SseParser::default()
        .push(&vec![b'x'; 2 * 1024 * 1024 + 1])
        .is_err());
    assert!(SseParser::default().push(&[0xff, b'\n']).is_err());
}
