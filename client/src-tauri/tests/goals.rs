use axum::{
    http::HeaderMap,
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
fn saved_goal() -> serde_json::Value {
    json!({"id":"g","projectId":"p","sessionId":"session","objective":"Produce result","status":"RUNNING","currentRunId":"goal-run","reason":"","maxRuns":3,"maxLlmCalls":20,"attempts":1,"llmCallsUsed":2,"criteria":[{"relativeFile":"out.txt","sha256":"a".repeat(64)}]})
}
#[tokio::test]
async fn goal_verification_cancellation_and_history_decode_the_existing_contracts() {
    let mut verified = saved_goal();
    verified["status"] = json!("COMPLETED");
    let mut cancelled = saved_goal();
    cancelled["status"] = json!("CANCELLED");
    let app=Router::new().route("/api/v1/projects/p/goals/g",get(||async{Json(saved_goal())})).route("/api/v1/projects/p/goals/g/history",get(||async{Json(json!({"history":[{"status":"RUNNING","reason":"attempt","timestamp":"2026-10-04T00:00:00Z"}],"attempts":[{"runId":"goal-run","number":1,"status":"RUNNING","reason":""}]}))})).route("/api/v1/projects/p/goals/g/verify",post(move|headers:HeaderMap|async move{assert_eq!(headers["authorization"],"Bearer secret");Json(json!({"goal":verified,"verification":{"satisfied":true,"reason":"file_digest_verified"}}))})).route("/api/v1/projects/p/goals/g/cancel",post(move|headers:HeaderMap|async move{assert_eq!(headers["authorization"],"Bearer secret");Json(cancelled)}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    let history = api
        .workspace(WorkspaceOperation::GoalHistory {
            project_id: "p".into(),
            id: "g".into(),
        })
        .await
        .unwrap();
    assert!(history.items[0]
        .fields
        .iter()
        .any(|(key, value)| key == "Attempts" && value.contains("goal-run")));
    let result = api
        .workspace(WorkspaceOperation::GoalVerify {
            project_id: "p".into(),
            id: "g".into(),
        })
        .await
        .unwrap();
    assert!(result.items[0]
        .fields
        .contains(&("Satisfied".into(), "true".into())));
    let result = api
        .workspace(WorkspaceOperation::GoalCancel {
            project_id: "p".into(),
            id: "g".into(),
        })
        .await
        .unwrap();
    assert!(result.items[0]
        .fields
        .contains(&("Status".into(), "CANCELLED".into())));
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
async fn native_goal_tracking_uses_actual_run_and_reuses_projection_without_posting() {
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    };
    let posts = Arc::new(AtomicUsize::new(0));
    let wrote = posts.clone();
    let app=Router::new().route("/api/v1/projects",get(||async{Json(json!([{"id":"p","name":"Project","path":"/project"}]))})).route("/api/v1/projects/p/goals/g",get(|headers:HeaderMap|async move{assert_eq!(headers["authorization"],"Bearer secret");Json(saved_goal())})).route("/api/v1/projects/p/goals/g/run",post(move||{let wrote=wrote.clone();async move{wrote.fetch_add(1,Ordering::SeqCst);Json(saved_goal())}})).route("/api/v1/runs/goal-run",get(||async{Json(json!({"runId":"goal-run","projectId":"p","sessionId":"session","turnId":null,"status":"QUEUED","failure":null}))})).route("/api/v1/runs/goal-run/events",get(||async{([("content-type","text/event-stream")],format!("event: agent.run.completed\nid: 1\ndata: {}\n\n",json!({"type":"agent.run.completed","version":1,"sequence":1,"runId":"goal-run","sessionId":"session","turnId":null,"projectId":"p","payload":{}})))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let dir = tempfile::tempdir().unwrap();
    let app = rei_client_lib::application::Application::open(
        dir.path().into(),
        Arc::new(Sink),
        Arc::new(Sink),
    )
    .unwrap();
    app.unlock(Secret::new("a long passphrase".into())).unwrap();
    let server = app.save_server(None, "Home", &url).unwrap();
    app.set_credential(&server, Secret::new("secret".into()))
        .unwrap();
    let run = app.goal_track(&server, "p", "g").await.unwrap();
    assert_eq!(run.run_id, "goal-run");
    assert_eq!(run.session_id, "session");
    app.goal_track(&server, "p", "g").await.unwrap();
    assert_eq!(app.runs.all().len(), 1);
    assert_eq!(posts.load(Ordering::SeqCst), 0);
    task.abort();
}
#[tokio::test]
async fn unacknowledged_reconciliation_and_foreign_run_session_are_rejected() {
    let app=Router::new().route("/api/v1/projects/p/goals/g",get(||async{Json(saved_goal())})).route("/api/v1/runs/goal-run",get(||async{Json(json!({"runId":"goal-run","projectId":"p","sessionId":"foreign","turnId":null,"status":"QUEUED","failure":null}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert!(matches!(
        api.workspace(WorkspaceOperation::GoalReconcile {
            project_id: "p".into(),
            id: "g".into(),
            expected_run_id: "goal-run".into(),
            acknowledge_uncertain_side_effects: false
        })
        .await,
        Err(AppError::InvalidInput)
    ));
    assert!(matches!(
        api.goal_snapshot("p", "g").await,
        Err(AppError::InvalidResponse)
    ));
    task.abort();
}
#[tokio::test]
async fn explicit_goal_reconciliation_retains_owner_and_requires_saved_run_acknowledgement() {
    let goal = json!({"id":"g","projectId":"p","sessionId":"session","objective":"Produce result","status":"RUNNING","currentRunId":"old","reason":"","maxRuns":3,"maxLlmCalls":20,"attempts":1,"llmCallsUsed":2,"criteria":[{"relativeFile":"out.txt","sha256":"a".repeat(64)}]});
    let listed = goal.clone();
    let mut paused = goal;
    paused["status"] = json!("PAUSED");
    paused["reason"] = json!("uncertain_run_reconciled");
    let app = Router::new()
        .route(
            "/api/v1/projects/p/goals",
            get(move |headers: HeaderMap| async move {
                assert_eq!(headers["authorization"], "Bearer secret");
                Json(json!([listed]))
            }),
        )
        .route(
            "/api/v1/projects/p/goals/g/reconcile",
            post(
                move |headers: HeaderMap, Json(body): Json<serde_json::Value>| async move {
                    assert_eq!(headers["authorization"], "Bearer secret");
                    assert_eq!(
                        body,
                        json!({"expectedRunId":"old","acknowledgeUncertainSideEffects":true})
                    );
                    Json(paused)
                },
            ),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert_eq!(
        api.workspace(WorkspaceOperation::Goals {
            project_id: "p".into()
        })
        .await
        .unwrap()
        .items[0]
            .title,
        "Produce result"
    );
    let result = api
        .workspace(WorkspaceOperation::GoalReconcile {
            project_id: "p".into(),
            id: "g".into(),
            expected_run_id: "old".into(),
            acknowledge_uncertain_side_effects: true,
        })
        .await
        .unwrap();
    assert!(result.items[0]
        .fields
        .contains(&("Status".into(), "PAUSED".into())));
    task.abort();
}
