use rei_client_lib::domain::*;

#[test]
fn profile_rejects_credentials_in_url() {
    for url in [
        "https://user:secret@rei.example",
        "https://rei.example/?key=secret",
        "file:///tmp/key",
        "https://rei.example/#secret",
    ] {
        assert!(ServerProfile::new("home", url).is_err());
    }
}

#[test]
fn profile_round_trip_is_secret_free() {
    let profile = ServerProfile::new("home", "https://rei.example/").unwrap();
    let json = serde_json::to_string(&profile).unwrap();
    let restored: ServerProfile = serde_json::from_str(&json).unwrap();
    assert_eq!(restored.base_url, "https://rei.example");
    assert_eq!(restored.id, profile.id);
    assert!(!json.contains("apiKey"));
}

#[test]
fn secret_debug_and_error_never_expose_key() {
    let secret = Secret::new("very-secret-key".into());
    assert!(!format!("{secret:?}").contains("very-secret-key"));
    assert_eq!(
        serde_json::to_string(&AppError::AuthenticationFailed).unwrap(),
        "\"AuthenticationFailed\""
    );
}

#[test]
fn http_errors_are_operation_specific() {
    assert_eq!(
        http_error(401, Operation::Chat),
        AppError::AuthenticationFailed
    );
    assert_eq!(http_error(404, Operation::Chat), AppError::SessionNotFound);
    assert_eq!(
        http_error(409, Operation::Chat),
        AppError::SessionProjectConflict
    );
    assert_eq!(http_error(404, Operation::Run), AppError::RunNotFound);
    assert_eq!(http_error(409, Operation::Stream), AppError::ReplayGap);
}

#[test]
fn run_status_wire_contract_includes_all_five_states() {
    for (name, status, terminal) in [
        ("QUEUED", RunStatus::Queued, false),
        ("RUNNING", RunStatus::Running, false),
        ("COMPLETED", RunStatus::Completed, true),
        ("FAILED", RunStatus::Failed, true),
        ("CANCELLED", RunStatus::Cancelled, true),
    ] {
        let decoded: RunStatus = serde_json::from_str(&format!("\"{name}\"")).unwrap();
        assert_eq!(decoded, status);
        assert_eq!(decoded.terminal(), terminal);
    }
}
