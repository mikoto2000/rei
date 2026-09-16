use axum::{
    http::{HeaderMap, StatusCode},
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::{json, Value};

async fn server() -> String {
    let app = Router::new()
        .route("/actuator/health", get(|| async { Json(json!({"status":"UP"})) }))
        .route("/api/v1/projects", get(|headers: HeaderMap| async move {
            if headers.get("authorization").and_then(|v| v.to_str().ok()) != Some("Bearer test-key") { return (StatusCode::UNAUTHORIZED, Json(json!({}))); }
            (StatusCode::OK, Json(json!([{"id":"p1","name":"rei","path":"/a"},{"id":"p2","name":"rei","path":"/b"}])))
        }))
        .route("/api/v1/chat", post(|Json(body): Json<Value>| async move {
            if body["projectId"] == "missing" { return (StatusCode::NOT_FOUND, Json(json!({}))); }
            if body["sessionId"] == "expired" { return (StatusCode::NOT_FOUND, Json(json!({}))); }
            if body["sessionId"] == "other-project" { return (StatusCode::CONFLICT, Json(json!({}))); }
            assert_eq!(body["projectId"], "p1");
            assert_eq!(body["message"], "hello");
            (StatusCode::ACCEPTED, Json(json!({"runId":"r1","sessionId":body["sessionId"].as_str().unwrap_or("new"),"turnId":"t1"})))
        }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    address
}
#[tokio::test]
async fn missing_project_is_not_an_expired_new_session() {
    let api = HttpReiClient::new(&server().await, Some(Secret::new("test-key".into()))).unwrap();
    assert_eq!(
        api.chat("missing", None, "hello").await.unwrap_err(),
        AppError::ProjectNotFound
    );
}

#[tokio::test]
async fn empty_projects_is_success_and_redirects_are_not_followed() {
    let app = Router::new()
        .route("/api/v1/projects", get(|| async { Json(json!([])) }))
        .route(
            "/actuator/health",
            get(|| async { StatusCode::TEMPORARY_REDIRECT }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("key".into()))).unwrap();
    assert!(api.projects().await.unwrap().is_empty());
    assert!(api.health().await.is_err());
}

#[test]
fn mobile_transport_requires_https() {
    assert!(rei_client_lib::api::validate_transport("http://192.168.1.2:8080", true).is_err());
    assert!(rei_client_lib::api::validate_transport("https://rei.example", true).is_ok());
    assert!(rei_client_lib::api::validate_transport("http://127.0.0.1:8080", false).is_ok());
}
#[tokio::test]
async fn reachable_and_authentication_are_independent() {
    let url = server().await;
    let api = HttpReiClient::new(&url, Some(Secret::new("wrong".into()))).unwrap();
    assert!(api.health().await.is_ok());
    assert_eq!(
        api.projects().await.unwrap_err(),
        AppError::AuthenticationFailed
    );
}
#[tokio::test]
async fn list_preserves_duplicate_names_and_server_paths() {
    let api = HttpReiClient::new(&server().await, Some(Secret::new("test-key".into()))).unwrap();
    let projects = api.projects().await.unwrap();
    assert_eq!(projects.len(), 2);
    assert_eq!(projects[1].id, "p2");
    assert_eq!(projects[1].path, "/b");
}
#[tokio::test]
async fn chat_retains_ids_and_never_retries_expired_sessions() {
    let api = HttpReiClient::new(&server().await, Some(Secret::new("test-key".into()))).unwrap();
    for session in [None, Some("existing")] {
        let reply = api.chat("p1", session, "hello").await.unwrap();
        assert_eq!(reply.run_id, "r1");
        assert_eq!(reply.turn_id, "t1");
        assert_eq!(reply.session_id, session.unwrap_or("new"));
    }
    assert_eq!(
        api.chat("p1", Some("expired"), "hello").await.unwrap_err(),
        AppError::SessionNotFound
    );
    assert_eq!(
        api.chat("p1", Some("other-project"), "hello")
            .await
            .unwrap_err(),
        AppError::SessionProjectConflict
    );
}
#[tokio::test]
async fn unreachable_is_a_safe_application_error() {
    let api = HttpReiClient::new("http://127.0.0.1:1", None).unwrap();
    assert_eq!(api.health().await.unwrap_err(), AppError::ServerUnreachable);
}
