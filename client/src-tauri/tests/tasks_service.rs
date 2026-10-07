use axum::{routing::get, Json, Router};
use rei_client_lib::{application::*, domain::*, ports::*};
use serde_json::json;
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
#[tokio::test]
async fn native_restart_fetches_server_task_receipts_without_recreating_runs() {
    let router=Router::new().route("/api/v1/tasks",get(||async{Json(json!({"items":[{"id":"run:r","kind":"RUN","sourceId":"r","projectId":"p","sessionId":"s","runId":"r","status":"UNKNOWN","mode":"EXCLUSIVE","startedAt":null,"updatedAt":null,"waitingReason":null,"errorSummary":"owner lost","progress":null,"results":[],"goalId":null,"dependencyIds":[],"schedulerId":null,"parentId":null,"childIds":[],"checkpointTaskId":null,"cancelSupported":false,"resumeSupported":false,"inputSupported":false,"revision":0}],"nextCursor":null}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let directory = tempfile::tempdir().unwrap();
    let app = Application::open(directory.path().into(), Arc::new(Sink), Arc::new(Sink)).unwrap();
    app.unlock(Secret::new("long passphrase".into())).unwrap();
    let server = app.save_server(None, "Server", &url).unwrap();
    app.set_credential(&server, Secret::new("key".into()))
        .unwrap();
    assert_eq!(
        app.tasks_list(&server, None, None, Some(1), None)
            .await
            .unwrap()
            .items[0]
            .id,
        "run:r"
    );
    drop(app);
    let reopened =
        Application::open(directory.path().into(), Arc::new(Sink), Arc::new(Sink)).unwrap();
    reopened
        .unlock(Secret::new("long passphrase".into()))
        .unwrap();
    assert_eq!(
        reopened
            .tasks_list(&server, None, None, Some(1), None)
            .await
            .unwrap()
            .items[0]
            .status,
        "UNKNOWN"
    );
    assert!(reopened.runs.all().is_empty());
}
#[tokio::test]
async fn stale_task_revision_is_rejected_before_any_write() {
    use std::sync::atomic::{AtomicUsize, Ordering};
    let writes = Arc::new(AtomicUsize::new(0));
    let received = writes.clone();
    let router=Router::new().route("/api/v1/tasks/{id}",get(||async{Json(json!({"id":"run:r","kind":"RUN","sourceId":"r","projectId":"p","sessionId":"s","runId":"r","status":"RUNNING","mode":"EXCLUSIVE","startedAt":null,"updatedAt":null,"waitingReason":null,"errorSummary":null,"progress":null,"results":[],"goalId":null,"dependencyIds":[],"schedulerId":null,"parentId":null,"childIds":[],"checkpointTaskId":null,"cancelSupported":true,"resumeSupported":false,"inputSupported":false,"revision":8}))}))
        .route("/api/v1/tasks/{id}/cancel",axum::routing::post(move||{let received=received.clone();async move{received.fetch_add(1,Ordering::SeqCst);axum::http::StatusCode::INTERNAL_SERVER_ERROR}}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let directory = tempfile::tempdir().unwrap();
    let app = Application::open(directory.path().into(), Arc::new(Sink), Arc::new(Sink)).unwrap();
    app.unlock(Secret::new("long passphrase".into())).unwrap();
    let server = app.save_server(None, "Server", &url).unwrap();
    app.set_credential(&server, Secret::new("key".into()))
        .unwrap();
    assert_eq!(
        app.task_control(
            &server,
            "p",
            Some("s"),
            "run:r",
            Some("r"),
            7,
            TaskAction::Cancel,
            None
        )
        .await
        .unwrap_err(),
        AppError::Conflict
    );
    assert_eq!(writes.load(Ordering::SeqCst), 0);
    assert!(app.runs.all().is_empty());
}
