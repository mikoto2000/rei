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
#[tokio::test]
async fn submit_registers_subscribes_resumes_and_expiry_does_not_fork() {
    let router=Router::new().route("/api/v1/chat",post(|Json(body):Json<Value>| async move {
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
