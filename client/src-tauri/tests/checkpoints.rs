use axum::{
    http::{HeaderMap, StatusCode},
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
#[tokio::test]
async fn checkpoint_resume_preserves_owner_and_returns_authoritative_run() {
    let checkpoint = json!({"schemaVersion":1,"revision":3,"taskId":"task","projectId":"p","sessionId":"session","runId":"old","request":"expected outcome","status":"INTERRUPTED","nextAction":"inspect","blockers":[],"operations":[]});
    let list = checkpoint.clone();
    let app=Router::new()
 .route("/api/v1/projects/p/checkpoints",get(move||async move{Json(json!([list]))}))
 .route("/api/v1/projects/p/checkpoints/task",get(move||async move{Json(checkpoint)}))
 .route("/api/v1/projects/p/checkpoints/task/reconciliation",get(||async{Json(json!({"decision":"CONTINUE","usable":[],"changed":[],"recheck":[],"unknownOperations":[],"blockers":[],"nextAction":"verify"}))}))
 .route("/api/v1/projects/p/checkpoints/task/resume",post(|headers:HeaderMap|async move{assert_eq!(headers["authorization"],"Bearer secret");(StatusCode::ACCEPTED,Json(json!({"taskId":"task","runId":"new","previousRunId":"old","checkpointRevision":3,"reconciliation":{"decision":"CONTINUE","usable":[],"changed":[],"recheck":[],"unknownOperations":[],"blockers":[],"nextAction":"verify"}})))}))
 .route("/api/v1/runs/new",get(||async{Json(json!({"runId":"new","projectId":"p","sessionId":"session","turnId":null,"status":"QUEUED","failure":null}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert_eq!(
        api.workspace(WorkspaceOperation::Checkpoints {
            project_id: "p".into()
        })
        .await
        .unwrap()
        .items
        .len(),
        1
    );
    assert_eq!(
        api.workspace(WorkspaceOperation::CheckpointInspect {
            project_id: "p".into(),
            task_id: "task".into()
        })
        .await
        .unwrap()
        .items
        .len(),
        1
    );
    let receipt = api.resume_checkpoint("p", "task").await.unwrap();
    assert_eq!(receipt.snapshot.run_id, "new");
    assert_eq!(receipt.snapshot.session_id, "session");
    task.abort();
}

struct Sink;
impl rei_client_lib::ports::RunObserver for Sink {
    fn changed(&self, _run: rei_client_lib::application::RunView) {}
}
impl rei_client_lib::ports::NotificationPort for Sink {
    fn notify(&self, _run: &rei_client_lib::application::RunView) -> Result<()> {
        Ok(())
    }
}
#[tokio::test]
async fn native_application_tracks_the_resumed_run_and_track_never_reposts_resume() {
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    };
    let posts = Arc::new(AtomicUsize::new(0));
    let reads = posts.clone();
    let writes = posts.clone();
    let app=Router::new()
 .route("/api/v1/projects",get(||async{Json(json!([{"id":"p","name":"Project","path":"/project"}]))}))
 .route("/api/v1/projects/p/checkpoints/task",get(move||{let posts=reads.clone();async move{Json(json!({"schemaVersion":1,"revision":3,"taskId":"task","projectId":"p","sessionId":"session","runId":if posts.load(Ordering::SeqCst)>0 {"new"}else{"old"},"request":"expected","status":"INTERRUPTED","nextAction":"inspect","blockers":[],"operations":[]}))}}))
 .route("/api/v1/projects/p/checkpoints/task/resume",post(move||{let posts=writes.clone();async move{posts.fetch_add(1,Ordering::SeqCst);(StatusCode::ACCEPTED,Json(json!({"taskId":"task","runId":"new","previousRunId":"old","checkpointRevision":3,"reconciliation":{"decision":"CONTINUE","usable":[],"changed":[],"recheck":[],"unknownOperations":[],"blockers":[],"nextAction":"verify"}})))}}))
 .route("/api/v1/runs/new",get(||async{Json(json!({"runId":"new","projectId":"p","sessionId":"session","turnId":null,"status":"QUEUED","failure":null}))}))
 .route("/api/v1/runs/new/events",get(||async{([("content-type","text/event-stream")],format!("event: agent.run.completed\nid: 1\ndata: {}\n\n",json!({"type":"agent.run.completed","version":1,"sequence":1,"runId":"new","sessionId":"session","turnId":null,"projectId":"p","payload":{}})))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let dir = tempfile::tempdir().unwrap();
    let client = rei_client_lib::application::Application::open(
        dir.path().into(),
        Arc::new(Sink),
        Arc::new(Sink),
    )
    .unwrap();
    client
        .unlock(Secret::new("a long passphrase".into()))
        .unwrap();
    let server = client.save_server(None, "Home", &url).unwrap();
    client
        .set_credential(&server, Secret::new("secret".into()))
        .unwrap();
    let run = client.checkpoint(&server, "p", "task", true).await.unwrap();
    assert_eq!(run.run_id, "new");
    assert_eq!(run.session_id, "session");
    let tracked = client
        .checkpoint(&server, "p", "task", false)
        .await
        .unwrap();
    assert_eq!(tracked.run_id, "new");
    assert_eq!(posts.load(Ordering::SeqCst), 1);
    assert_eq!(client.runs.all().len(), 1);
    task.abort();
}

#[tokio::test]
async fn unowned_saved_state_and_post_acceptance_run_mismatch_never_retry_resume() {
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    };
    let posts = Arc::new(AtomicUsize::new(0));
    let writes = posts.clone();
    let good = json!({"schemaVersion":1,"revision":3,"taskId":"task","projectId":"p","sessionId":"session","runId":"old","request":"expected","status":"INTERRUPTED","nextAction":"inspect","blockers":[],"operations":[]});
    let mut foreign = good.clone();
    foreign["projectId"] = json!("other");
    foreign["taskId"] = json!("foreign");
    let app=Router::new()
 .route("/api/v1/projects/p/checkpoints/task",get(move||async move{Json(good)}))
 .route("/api/v1/projects/p/checkpoints/foreign",get(move||async move{Json(foreign)}))
 .route("/api/v1/projects/p/checkpoints/task/resume",post(move||{let posts=writes.clone();async move{posts.fetch_add(1,Ordering::SeqCst);(StatusCode::ACCEPTED,Json(json!({"taskId":"task","runId":"new","previousRunId":"old","checkpointRevision":3,"reconciliation":{"decision":"CONTINUE","usable":[],"changed":[],"recheck":[],"unknownOperations":[],"blockers":[],"nextAction":"verify"}})))}}))
 .route("/api/v1/runs/new",get(||async{Json(json!({"runId":"new","projectId":"p","sessionId":"foreign-session","turnId":null,"status":"QUEUED","failure":null}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert!(matches!(
        api.resume_checkpoint("p", "foreign").await,
        Err(AppError::InvalidResponse)
    ));
    assert_eq!(posts.load(Ordering::SeqCst), 0);
    assert!(matches!(
        api.resume_checkpoint("p", "task").await,
        Err(AppError::InvalidResponse)
    ));
    assert_eq!(posts.load(Ordering::SeqCst), 1);
    task.abort();
}
