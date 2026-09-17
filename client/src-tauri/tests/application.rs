use axum::{
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{application::*, domain::*, ports::*};
use serde_json::{json, Value};
use std::sync::Arc;
struct Sink;
impl RunObserver for Sink {
    fn changed(&self, _: RunView) {}
}
impl NotificationPort for Sink {
    fn notify(&self, _: &RunView) -> Result<()> {
        Ok(())
    }
}
fn app(path: &std::path::Path) -> Application {
    Application::open(path.into(), Arc::new(Sink), Arc::new(Sink)).unwrap()
}

#[tokio::test]
async fn connection_test_keeps_http_errors_separate_from_unreachable() {
    for (health, projects, expected, state) in [
        (
            401,
            200,
            AppError::HealthAuthenticationRequired,
            ConnectionState::ConnectionFailed,
        ),
        (
            403,
            200,
            AppError::HealthAuthenticationRequired,
            ConnectionState::ConnectionFailed,
        ),
        (
            404,
            200,
            AppError::EndpointNotFound,
            ConnectionState::ConnectionFailed,
        ),
        (
            503,
            200,
            AppError::UnexpectedServerError,
            ConnectionState::ConnectionFailed,
        ),
        (
            302,
            200,
            AppError::HttpRedirect,
            ConnectionState::ConnectionFailed,
        ),
        (
            200,
            401,
            AppError::AuthenticationFailed,
            ConnectionState::AuthFailed,
        ),
        (
            200,
            403,
            AppError::PermissionDenied,
            ConnectionState::ConnectionFailed,
        ),
        (
            200,
            404,
            AppError::EndpointNotFound,
            ConnectionState::ConnectionFailed,
        ),
        (
            200,
            429,
            AppError::RateLimited,
            ConnectionState::ConnectionFailed,
        ),
        (
            200,
            500,
            AppError::UnexpectedServerError,
            ConnectionState::ConnectionFailed,
        ),
        (
            200,
            400,
            AppError::RequestRejected,
            ConnectionState::ConnectionFailed,
        ),
        (
            200,
            200,
            AppError::InvalidResponse,
            ConnectionState::ConnectionFailed,
        ),
    ] {
        let router = Router::new()
            .route(
                "/actuator/health",
                get(move || async move { StatusCode::from_u16(health).unwrap() }),
            )
            .route(
                "/api/v1/projects",
                get(move || async move {
                    (
                        StatusCode::from_u16(projects).unwrap(),
                        "private-response-must-not-leak",
                    )
                }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        let dir = tempfile::tempdir().unwrap();
        let app = app(dir.path());
        app.unlock(Secret::new("a long passphrase".into())).unwrap();
        let id = app.save_server(None, "Home", &url).unwrap();
        app.set_credential(&id, Secret::new("test-key".into()))
            .unwrap();
        let result = app.test_server(&id).await.unwrap();
        assert!(result.reachable, "health={health}, projects={projects}");
        assert!(!result.authenticated);
        assert_eq!(result.error, Some(expected));
        assert_eq!(result.state, state);
        assert!(!serde_json::to_string(&result)
            .unwrap()
            .contains("private-response"));
        task.abort();
    }
}

#[tokio::test]
async fn connection_test_finishes_when_client_cannot_be_created() {
    let dir = tempfile::tempdir().unwrap();
    let app = app(dir.path());
    let result = app.test_server("missing-profile").await.unwrap();
    assert_eq!(result.state, ConnectionState::ConnectionFailed);
    assert_eq!(result.error, Some(AppError::NotFound));
    assert!(!result.reachable);
}

#[tokio::test]
async fn connection_test_success_and_locked_vault_preserve_reachability() {
    let router = Router::new()
        .route("/actuator/health", get(|| async { StatusCode::OK }))
        .route(
            "/api/v1/projects",
            get(|headers: axum::http::HeaderMap| async move {
                assert_eq!(headers["authorization"], "Bearer test-key");
                Json(json!([]))
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let dir = tempfile::tempdir().unwrap();
    let app = app(dir.path());
    let id = app.save_server(None, "Home", &url).unwrap();
    let locked = app.test_server(&id).await.unwrap();
    assert!(locked.reachable);
    assert_eq!(locked.error, Some(AppError::VaultLocked));
    app.unlock(Secret::new("a long passphrase".into())).unwrap();
    assert_eq!(
        app.test_server(&id).await.unwrap().error,
        Some(AppError::AuthenticationFailed)
    );
    app.set_credential(&id, Secret::new("test-key".into()))
        .unwrap();
    let connected = app.test_server(&id).await.unwrap();
    assert_eq!(connected.state, ConnectionState::Connected);
    assert!(connected.reachable && connected.authenticated);
    assert_eq!(connected.error, None);
    task.abort();
}
#[test]
fn server_crud_and_credential_dto() {
    let dir = tempfile::tempdir().unwrap();
    let app = app(dir.path());
    app.unlock(Secret::new("a long passphrase".into())).unwrap();
    let id = app
        .save_server(None, "Home", "https://rei.example")
        .unwrap();
    app.set_credential(&id, Secret::new("api-key-private".into()))
        .unwrap();
    app.select_server(&id).unwrap();
    let servers = app.servers().unwrap();
    assert!(servers[0].has_credential);
    assert!(!serde_json::to_string(&servers)
        .unwrap()
        .contains("api-key-private"));
    app.save_server(Some(&id), "Renamed", "https://rei.example")
        .unwrap();
    assert_eq!(app.servers().unwrap()[0].name, "Renamed");
    app.delete_credential(&id).unwrap();
    assert!(!app.servers().unwrap()[0].has_credential);
    app.remove_server(&id).unwrap();
    assert!(app.servers().unwrap().is_empty());
}

#[test]
fn live_activity_is_not_restored_when_application_reopens() {
    let dir = tempfile::tempdir().unwrap();
    let application = app(dir.path());
    let server = application
        .save_server(None, "Home", "https://rei.example")
        .unwrap();
    let mut projection = Projection::new(
        &server,
        "conversation",
        "p",
        ChatReceipt {
            run_id: "r".into(),
            session_id: "s".into(),
            turn_id: "t".into(),
        },
        "hello",
    );
    let data = json!({"type":"llm.request.started","sequence":1,"version":1,"runId":"r",
        "payload":{"requestId":"runtime-request","feature":"runtime-only-activity"}});
    let bytes = format!("event: llm.request.started\nid: 1\ndata: {data}\n\n");
    projection
        .apply(
            SseParser::default()
                .push(bytes.as_bytes())
                .unwrap()
                .remove(0),
        )
        .unwrap();
    application.runs.register(projection).unwrap();
    assert_eq!(application.runs.all()[0].activities.len(), 1);
    application.select_server(&server).unwrap();
    drop(application);
    let reopened = app(dir.path());
    assert_eq!(reopened.servers().unwrap().len(), 1);
    assert!(reopened.runs.all().is_empty());
    for entry in std::fs::read_dir(dir.path()).unwrap() {
        let entry = entry.unwrap();
        if entry.path().is_file() {
            assert!(
                !String::from_utf8_lossy(&std::fs::read(entry.path()).unwrap())
                    .contains("runtime-only-activity")
            );
        }
    }
}
#[tokio::test]
async fn submit_registers_subscribes_resumes_and_expiry_does_not_fork() {
    let router=Router::new().route("/api/v1/sessions/s",get(|| async { Json(json!({"sessionId":"s","projectId":"p","title":"first","createdAt":"2026-09-16T08:00:00Z","updatedAt":"2026-09-16T08:00:00Z"})) }))
    .route("/api/v1/chat",post(|Json(body):Json<Value>| async move {
        if body["message"]=="expired" {return (StatusCode::NOT_FOUND,Json(json!({})));}
        if body["message"]=="second" {assert_eq!(body["sessionId"],"s");}
        (StatusCode::ACCEPTED,Json(json!({"runId":if body["message"]=="second" {"r2"} else {"r"},"sessionId":"s","turnId":"t"})))
    })).route("/api/v1/runs/{id}/events",get(|axum::extract::Path(id):axum::extract::Path<String>|async move {
        ([("content-type","text/event-stream")],format!("event: agent.run.completed\nid: 1\ndata: {}\n\n",json!({"type":"agent.run.completed","sequence":1,"version":1,"runId":id,"payload":{}})))
    }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let dir = tempfile::tempdir().unwrap();
    let app = app(dir.path());
    app.unlock(Secret::new("a long passphrase".into())).unwrap();
    let id = app.save_server(None, "Home", &url).unwrap();
    app.set_credential(&id, Secret::new("key".into())).unwrap();
    let c = app.conversations.create(&id, "p", "").unwrap();
    let first = app.submit(&c.local_id, "first").await.unwrap();
    assert_eq!(first.run_id, "r");
    assert_eq!(app.conversations.get(&c.local_id).unwrap().title, "first");
    tokio::time::timeout(std::time::Duration::from_secs(3), async {
        while !app.runs.get(&id, "r").unwrap().status.terminal() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(
        app.conversations
            .get(&c.local_id)
            .unwrap()
            .session_id
            .as_deref(),
        Some("s")
    );
    assert!(app.submit(&c.local_id, "second").await.is_ok());
    tokio::time::timeout(std::time::Duration::from_secs(3), async {
        while !app.runs.get(&id, "r2").unwrap().status.terminal() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert!(matches!(
        app.submit(&c.local_id, "expired").await,
        Err(AppError::SessionNotFound)
    ));
    assert_eq!(app.conversations.list().len(), 1);
}
