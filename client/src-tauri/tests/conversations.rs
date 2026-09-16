use rei_client_lib::{
    application::ConversationService, domain::*, infrastructure::JsonRepository, ports::Repository,
};
use std::sync::Arc;

fn service() -> (tempfile::TempDir, ConversationService) {
    let dir = tempfile::tempdir().unwrap();
    let repo = Arc::new(JsonRepository::new(dir.path().join("app.json")));
    let mut data = AppData::default();
    let mut profile = ServerProfile::new("home", "https://rei.example").unwrap();
    profile.id = "server".into();
    data.servers.push(profile);
    repo.save(&data).unwrap();
    (dir, ConversationService::new(repo).unwrap())
}
#[test]
fn runtime_project_lock_does_not_persist_server_metadata() {
    let (dir, service) = service();
    let c = service.create("server", "p", "hello").unwrap();
    assert!(c.session_id.is_none());
    service.attach_session(&c.local_id, "s").unwrap();
    assert_eq!(
        service.change_project(&c.local_id, "other"),
        Err(AppError::SessionProjectConflict)
    );
    let restored =
        ConversationService::new(Arc::new(JsonRepository::new(dir.path().join("app.json"))))
            .unwrap();
    assert!(restored.list().is_empty());
    let json = std::fs::read_to_string(dir.path().join("app.json")).unwrap();
    assert!(!json.contains("conversations") && !json.contains("sessionId"));
}
#[test]
fn explicit_continue_creates_new_metadata_and_preserves_old_session() {
    let (_, service) = service();
    let c = service.create("server", "p", "hello").unwrap();
    service.attach_session(&c.local_id, "expired").unwrap();
    let next = service.continue_as_new(&c.local_id).unwrap();
    assert_ne!(c.local_id, next.local_id);
    assert_eq!(next.project_id, "p");
    assert!(next.session_id.is_none());
    assert_eq!(
        service.get(&c.local_id).unwrap().session_id.as_deref(),
        Some("expired")
    );
}
#[test]
fn new_conversation_can_change_project_but_cannot_switch_session() {
    let (_, service) = service();
    let c = service.create("server", "p", "hi").unwrap();
    service.change_project(&c.local_id, "q").unwrap();
    service.attach_session(&c.local_id, "s").unwrap();
    assert_eq!(
        service.attach_session(&c.local_id, "other"),
        Err(AppError::SessionProjectConflict)
    );
}
#[test]
fn delete_and_invalid_server() {
    let (_, service) = service();
    assert!(service.create("unknown", "p", "hi").is_err());
    let c = service.create("server", "p", "hi").unwrap();
    service.delete(&c.local_id).unwrap();
    assert!(service.list().is_empty());
}
