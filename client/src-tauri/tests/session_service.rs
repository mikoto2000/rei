use axum::{
    extract::Path,
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{application::*, domain::*, ports::*};
use serde_json::{json, Value};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
struct Sink;
impl RunObserver for Sink {
    fn changed(&self, _: RunView) {}
}
impl NotificationPort for Sink {
    fn notify(&self, _: &RunView) -> Result<()> {
        Ok(())
    }
}
fn metadata(id: &str) -> Value {
    json!({"sessionId":id,"projectId":"server-project","title":"Server title 😀","createdAt":"2026-09-16T08:00:00Z","updatedAt":"2026-09-16T09:00:00Z"})
}
async fn setup() -> (tempfile::TempDir, Application, String, Arc<AtomicUsize>) {
    let posts = Arc::new(AtomicUsize::new(0));
    let count = posts.clone();
    let router = Router::new()
        .route("/api/v1/sessions", get(|| async { Json(json!({"items":[metadata("remote")],"nextCursor":null})) }))
        .route("/api/v1/sessions/{id}", get(|Path(id):Path<String>| async move {
            if id == "missing" { (StatusCode::NOT_FOUND, Json(json!({}))) }
            else { (StatusCode::OK, Json(metadata(&id))) }
        }))
        .route("/api/v1/sessions/{id}/turns", get(|Path(id):Path<String>| async move { Json(json!({"sessionId":id,"items":[{"turnId":"old","runId":"old","userMessage":"old question","assistantMessage":"old answer","createdAt":"2026-09-16T08:00:00Z"}],"nextCursor":null})) }))
        .route("/api/v1/chat", post(move |Json(body):Json<Value>| { let count=count.clone(); async move {
            count.fetch_add(1,Ordering::SeqCst);
            assert_eq!(body["projectId"], "server-project"); assert_eq!(body["sessionId"], "remote");
            if body["message"] == "conflict" { return (StatusCode::CONFLICT, Json(json!({}))); }
            if body["message"] == "gone" { return (StatusCode::NOT_FOUND, Json(json!({}))); }
            (StatusCode::ACCEPTED,Json(json!({"runId":"r","turnId":"r","sessionId":"remote"})))
        }}))
        .route("/api/v1/runs/r/events", get(|| async {
            ([("content-type","text/event-stream")], format!("event: message.delta\nid: 1\ndata: {}\n\nevent: agent.run.completed\nid: 2\ndata: {}\n\n",
                json!({"type":"message.delta","sequence":1,"version":1,"runId":"r","payload":{"messageId":"m","delta":"resumed answer"}}),
                json!({"type":"agent.run.completed","sequence":2,"version":1,"runId":"r","payload":{}})))
        }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let dir = tempfile::tempdir().unwrap();
    let app = Application::open(dir.path().into(), Arc::new(Sink), Arc::new(Sink)).unwrap();
    app.unlock(Secret::new("long passphrase for test".into()))
        .unwrap();
    let server = app.save_server(None, "Server", &url).unwrap();
    app.set_credential(&server, Secret::new("key".into()))
        .unwrap();
    (dir, app, server, posts)
}
#[tokio::test]
async fn remote_session_discovery_resume_and_sse_use_existing_run_manager() {
    let (dir, app, server, posts) = setup().await;
    let page = app.session_list(&server, None, None, None).await.unwrap();
    let c = app
        .resume_session(&server, &page.items[0].session_id)
        .await
        .unwrap();
    assert_eq!(c.project_id, "server-project");
    assert_eq!(c.title, "Server title 😀");
    assert_eq!(
        app.session_turns(&server, "remote", None, None)
            .await
            .unwrap()
            .items[0]
            .user_message,
        "old question"
    );
    assert_eq!(
        app.conversations
            .change_project(&c.local_id, "stale-selection"),
        Err(AppError::SessionProjectConflict)
    );
    let run = app.submit(&c.local_id, "continue").await.unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(3), async {
        while !app
            .runs
            .get(&server, &run.run_id)
            .unwrap()
            .status
            .terminal()
        {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(
        app.runs.get(&server, "r").unwrap().assistant_text,
        "resumed answer"
    );
    assert_eq!(posts.load(Ordering::SeqCst), 1);
    let stored = std::fs::read_to_string(dir.path().join("app.json")).unwrap();
    assert!(
        !stored.contains("Server title")
            && !stored.contains("server-project")
            && !stored.contains("old question")
    );
    let restarted = Application::open(dir.path().into(), Arc::new(Sink), Arc::new(Sink)).unwrap();
    restarted
        .unlock(Secret::new("long passphrase for test".into()))
        .unwrap();
    assert!(restarted.conversations.list().is_empty());
    assert_eq!(
        restarted
            .session_list(&server, None, None, None)
            .await
            .unwrap()
            .items[0]
            .session_id,
        "remote"
    );
}
#[tokio::test]
async fn session_not_found_and_conflict_never_create_a_replacement_or_retry_post() {
    let (_, app, server, posts) = setup().await;
    assert!(matches!(
        app.resume_session(&server, "missing").await,
        Err(AppError::SessionNotFound)
    ));
    assert!(app.conversations.list().is_empty());
    let c = app.resume_session(&server, "remote").await.unwrap();
    assert!(matches!(
        app.submit(&c.local_id, "conflict").await,
        Err(AppError::SessionProjectConflict)
    ));
    assert!(matches!(
        app.submit(&c.local_id, "gone").await,
        Err(AppError::SessionNotFound)
    ));
    assert_eq!(posts.load(Ordering::SeqCst), 2);
    assert_eq!(app.conversations.list().len(), 1);
    assert_eq!(
        app.conversations
            .get(&c.local_id)
            .unwrap()
            .session_id
            .as_deref(),
        Some("remote")
    );
}
