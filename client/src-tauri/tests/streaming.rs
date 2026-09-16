use axum::{
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use rei_client::{api::HttpReiClient, application::*, domain::*, ports::*};
use serde_json::json;
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc, Mutex,
};
use std::time::Duration;

#[derive(Default)]
struct Observer {
    notices: Mutex<Vec<RunStatus>>,
}
impl RunObserver for Observer {
    fn changed(&self, _: RunView) {}
}
impl NotificationPort for Observer {
    fn notify(&self, run: &RunView) -> Result<()> {
        self.notices.lock().unwrap().push(run.status);
        Err(AppError::Cancelled)
    }
}
async fn fixture(
    gap: bool,
) -> (
    Arc<RunManager>,
    Arc<Observer>,
    Arc<AtomicUsize>,
    Arc<dyn ReiClient>,
) {
    let connections = Arc::new(AtomicUsize::new(0));
    let count = connections.clone();
    let app=Router::new().route("/api/v1/runs/r/events",get(move |headers:HeaderMap| {
        let count=count.clone();async move {
            assert_eq!(headers["authorization"],"Bearer test-key");
            let attempt=count.fetch_add(1,Ordering::SeqCst);
            if attempt>0 {assert_eq!(headers["last-event-id"],"101");}
            if gap && attempt>0 {return StatusCode::CONFLICT.into_response();}
            let delta="event: message.delta\nid: 101\ndata: {\"type\":\"message.delta\",\"sequence\":101,\"version\":1,\"runId\":\"r\",\"payload\":{\"messageId\":\"m\",\"delta\":\"hello\"}}\n\n";
            let terminal="event: agent.run.completed\nid: 109\ndata: {\"type\":\"agent.run.completed\",\"sequence\":109,\"version\":1,\"runId\":\"r\",\"payload\":{}}\n\n";
            Response::builder().header("content-type","text/event-stream").body(axum::body::Body::from(if attempt==0 {delta.into()} else {format!("{delta}{terminal}")})).unwrap()
        }
    })).route("/api/v1/runs/r",get(||async {Json(json!({"runId":"r","projectId":"p","sessionId":"s","turnId":"t","status":"COMPLETED","failure":null}))}))
    .route("/api/v1/runs/r/cancel",post(||async {Json(json!({"runId":"r","projectId":"p","sessionId":"s","turnId":"t","status":"CANCELLED","failure":null}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let api: Arc<dyn ReiClient> = Arc::new(
        HttpReiClient::new(
            &format!("http://{}", listener.local_addr().unwrap()),
            Some(Secret::new("test-key".into())),
        )
        .unwrap(),
    );
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let observer = Arc::new(Observer::default());
    let manager = Arc::new(RunManager::new(
        observer.clone(),
        observer.clone(),
        Duration::from_millis(1),
    ));
    manager
        .register(Projection::new(
            "server",
            "conversation",
            "p",
            ChatReceipt {
                run_id: "r".into(),
                session_id: "s".into(),
                turn_id: "t".into(),
            },
            "hello",
        ))
        .unwrap();
    (manager, observer, connections, api)
}
async fn completed(manager: &RunManager) -> RunView {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let p = manager.get("server", "r").unwrap();
            if p.status.terminal() {
                return p;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap()
}
#[tokio::test]
async fn disconnect_reconnect_replays_once_and_permission_denial_is_harmless() {
    let (manager, observer, count, api) = fixture(false).await;
    manager.subscribe("server", "r", api.clone()).unwrap();
    manager.subscribe("server", "r", api).unwrap();
    let p = completed(&manager).await;
    assert_eq!(p.assistant_text, "hello");
    assert_eq!(p.status, RunStatus::Completed);
    assert!(!p.incomplete);
    assert_eq!(count.load(Ordering::SeqCst), 2);
    assert!(manager.active().is_empty());
    assert_eq!(
        *observer.notices.lock().unwrap(),
        vec![RunStatus::Completed]
    );
}
#[tokio::test]
async fn replay_gap_recovers_status_and_preserves_incomplete_flag() {
    let (manager, _, _, api) = fixture(true).await;
    manager.subscribe("server", "r", api).unwrap();
    let p = completed(&manager).await;
    assert!(p.incomplete);
    assert_eq!(p.status, RunStatus::Completed);
    assert_eq!(p.assistant_text, "hello");
}
#[tokio::test]
async fn cancel_is_explicit_and_unknown_run_fails() {
    let (manager, _, _, api) = fixture(false).await;
    assert_eq!(
        manager
            .cancel("server", "unknown", api.clone())
            .await
            .unwrap_err(),
        AppError::RunNotFound
    );
    manager.cancel("server", "r", api.clone()).await.unwrap();
    manager.cancel("server", "r", api).await.unwrap();
    assert_eq!(
        manager.get("server", "r").unwrap().status,
        RunStatus::Cancelled
    );
}
#[tokio::test]
async fn multiple_servers_can_have_the_same_run_id() {
    let (manager, _, _, _) = fixture(false).await;
    manager
        .register(Projection::new(
            "other-server",
            "other-conversation",
            "other-project",
            ChatReceipt {
                run_id: "r".into(),
                session_id: "s2".into(),
                turn_id: "t2".into(),
            },
            "hi",
        ))
        .unwrap();
    assert_eq!(manager.active().len(), 2);
    assert_eq!(
        manager.get("other-server", "r").unwrap().conversation_id,
        "other-conversation"
    );
    manager.unsubscribe("server", "r").unwrap();
    assert_eq!(
        manager.get("server", "r").unwrap().stream_state,
        StreamState::Closed
    );
}
