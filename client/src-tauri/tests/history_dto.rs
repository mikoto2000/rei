use rei_client_lib::{domain::*, dto::*};
#[test]
fn ui_boundary_has_explicit_camel_case_iso_dates_and_nullable_answer() {
    let session = SessionSummary::from_wire(
        "s",
        "p",
        "title".into(),
        "2026-09-16T08:00:00Z",
        "2026-09-16T08:00:00Z",
    )
    .unwrap();
    let dto = SessionPageDto::from(Page {
        items: vec![session],
        next_cursor: None,
    });
    let json = serde_json::to_value(dto).unwrap();
    assert_eq!(json["items"][0]["sessionId"], "s");
    assert_eq!(json["items"][0]["createdAt"], "2026-09-16T08:00:00Z");
    assert!(json["nextCursor"].is_null());
    let turn = ConversationTurnDto::from(ConversationTurn {
        run_id: "r".into(),
        user_message: "q".into(),
        assistant_message: None,
        source: None,
        source_id: None,
        metadata: Default::default(),
        created_at: timestamp("2026-09-16T08:00:00Z").unwrap(),
    });
    let json = serde_json::to_value(turn).unwrap();
    assert_eq!(json["turnId"], json["runId"]);
    assert!(json["assistantMessage"].is_null());
}
