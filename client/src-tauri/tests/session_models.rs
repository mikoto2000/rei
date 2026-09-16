use rei_client_lib::domain::*;

#[test]
fn history_query_validates_limits_without_interpreting_cursor() {
    let query = HistoryQuery::new(None, Some("opaque:+/?=&😀".into())).unwrap();
    assert_eq!(query.limit, 50);
    assert_eq!(query.cursor.as_deref(), Some("opaque:+/?=&😀"));
    for limit in [0, -1, 101] {
        assert_eq!(
            HistoryQuery::new(Some(limit), None).unwrap_err(),
            AppError::InvalidLimit
        );
    }
    for limit in [1, 100] {
        assert!(HistoryQuery::new(Some(limit), None).is_ok());
    }
}

#[test]
fn existing_target_keeps_session_and_project_together() {
    let session = SessionSummary::from_wire(
        "session",
        "project",
        "😀".repeat(90),
        "2026-09-16T08:00:00Z",
        "2026-09-16T10:00:00+00:00",
    )
    .unwrap();
    assert_eq!(session.title.chars().count(), 90);
    let target = ConversationTarget::Existing(session);
    assert_eq!(target.project_id(), "project");
    assert_eq!(target.session_id(), Some("session"));
    assert!(SessionSummary::from_wire("s", "p", "title".into(), "invalid", "invalid").is_err());
}
