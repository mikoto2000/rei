use axum::{
    http::HeaderMap,
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
fn entry(status: &str) -> serde_json::Value {
    json!({"task":{"id":"timer","createdAt":"2026-10-05T00:00:00Z","executeAt":"2026-10-05T01:00:00Z","action":"Inspect result","conversationId":"session"},"projectId":"p","projectRoot":"/project","status":status,"runId":"schedule-run","outcome":if status=="FAILED"{"uncertain_run_reconciled"}else{""}})
}
#[tokio::test]
async fn reads_controls_and_actual_run_preserve_project_and_session() {
    let app=Router::new()
 .route("/api/v1/projects/p/schedules",get(||async{Json(json!([entry("RUNNING")]))}))
 .route("/api/v1/projects/p/schedules/timer",get(||async{Json(json!({"schedule":entry("RUNNING"),"interval":{"intervalMillis":60000,"remaining":2},"cron":null,"eventTrigger":null}))}))
 .route("/api/v1/projects/p/schedules/timer/history",get(||async{Json(json!([{"status":"RUNNING","detail":"claimed"}]))}))
 .route("/api/v1/projects/p/schedules/timer/reconcile",post(|headers:HeaderMap,Json(body):Json<serde_json::Value>|async move{assert_eq!(headers["authorization"],"Bearer secret");assert_eq!(body,json!({"expectedRunId":"schedule-run","acknowledgeUncertainSideEffects":true}));Json(entry("FAILED"))}))
 .route("/api/v1/runs/schedule-run",get(||async{Json(json!({"runId":"schedule-run","projectId":"p","sessionId":"session","turnId":null,"status":"RUNNING","createdAt":"2026-10-05T00:00:00Z","updatedAt":"2026-10-05T00:00:00Z","lastEventSequence":0,"failure":null}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    let rows = api
        .workspace(WorkspaceOperation::Schedules {
            project_id: "p".into(),
        })
        .await
        .unwrap();
    assert_eq!(rows.items[0].id.as_deref(), Some("timer"));
    let history = api
        .workspace(WorkspaceOperation::ScheduleHistory {
            project_id: "p".into(),
            id: "timer".into(),
        })
        .await
        .unwrap();
    assert!(history.items[0]
        .fields
        .iter()
        .any(|(key, value)| key == "History" && value.contains("claimed")));
    assert_eq!(
        api.schedule_snapshot("p", "timer")
            .await
            .unwrap()
            .session_id,
        "session"
    );
    assert!(matches!(
        api.workspace(WorkspaceOperation::ScheduleReconcile {
            project_id: "p".into(),
            id: "timer".into(),
            expected_run_id: "schedule-run".into(),
            acknowledge_uncertain_side_effects: false
        })
        .await,
        Err(AppError::InvalidInput)
    ));
    let receipt = api
        .workspace(WorkspaceOperation::ScheduleReconcile {
            project_id: "p".into(),
            id: "timer".into(),
            expected_run_id: "schedule-run".into(),
            acknowledge_uncertain_side_effects: true,
        })
        .await
        .unwrap();
    assert!(receipt.items[0]
        .fields
        .contains(&("Status".into(), "FAILED".into())));
    task.abort();
}

struct Sink;
#[tokio::test]
async fn activation_cancellation_and_foreign_run_rejection_use_existing_contracts() {
    let app=Router::new()
      .route("/api/v1/projects/p/schedules/timer",get(||async{Json(json!({"schedule":entry("RUNNING")}))}))
      .route("/api/v1/projects/p/schedules/timer/activate",post(|headers:HeaderMap|async move{assert_eq!(headers["authorization"],"Bearer secret");Json(json!({"schedule":entry("SCHEDULED"),"cron":{"expression":"0 * * * * *","zone":"Asia/Tokyo","remaining":2}}))}))
      .route("/api/v1/projects/p/schedules/timer/cancel",post(|headers:HeaderMap|async move{assert_eq!(headers["authorization"],"Bearer secret");Json(json!({"schedule":entry("CANCELLED")}))}))
      .route("/api/v1/projects/p/schedules/timer/reconcile",post(||async{Json(entry("FAILED"))}))
      .route("/api/v1/projects/p/schedules/not-activated/activate",post(||async{let mut state=entry("PENDING");state["task"]["id"]=json!("not-activated");Json(json!({"schedule":state}))}))
      .route("/api/v1/runs/schedule-run",get(||async{Json(json!({"runId":"schedule-run","projectId":"p","sessionId":"foreign","turnId":null,"status":"RUNNING","failure":null}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    let activated = api
        .workspace(WorkspaceOperation::ScheduleActivate {
            project_id: "p".into(),
            id: "timer".into(),
        })
        .await
        .unwrap();
    assert!(activated.items[0]
        .fields
        .contains(&("Status".into(), "SCHEDULED".into())));
    assert!(activated.items[0]
        .fields
        .iter()
        .any(|(key, value)| key == "Cron" && value.contains("Asia/Tokyo")));
    let cancelled = api
        .workspace(WorkspaceOperation::ScheduleCancel {
            project_id: "p".into(),
            id: "timer".into(),
        })
        .await
        .unwrap();
    assert!(cancelled.items[0]
        .fields
        .contains(&("Status".into(), "CANCELLED".into())));
    assert!(matches!(
        api.workspace(WorkspaceOperation::ScheduleActivate {
            project_id: "p".into(),
            id: "not-activated".into()
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    assert!(matches!(
        api.schedule_snapshot("p", "timer").await,
        Err(AppError::InvalidResponse)
    ));
    assert!(matches!(
        api.workspace(WorkspaceOperation::ScheduleReconcile {
            project_id: "p".into(),
            id: "timer".into(),
            expected_run_id: "stale-run".into(),
            acknowledge_uncertain_side_effects: true
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    task.abort();
}
impl rei_client_lib::ports::RunObserver for Sink {
    fn changed(&self, _run: rei_client_lib::application::RunView) {}
}
impl rei_client_lib::ports::NotificationPort for Sink {
    fn notify(&self, _run: &rei_client_lib::application::RunView) -> Result<()> {
        Ok(())
    }
}
#[tokio::test]
async fn native_schedule_tracking_uses_actual_run_and_reuses_projection_without_posting() {
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    };
    let posts = Arc::new(AtomicUsize::new(0));
    let wrote = posts.clone();
    let app=Router::new().route("/api/v1/projects",get(||async{Json(json!([{"id":"p","name":"Project","path":"/project"}]))})).route("/api/v1/projects/p/schedules/timer",get(|headers:HeaderMap|async move{assert_eq!(headers["authorization"],"Bearer secret");Json(serde_json::json!({"schedule":entry("RUNNING"),"interval":null,"cron":null,"eventTrigger":null}))})).route("/api/v1/projects/p/schedules/timer/run",post(move||{let wrote=wrote.clone();async move{wrote.fetch_add(1,Ordering::SeqCst);Json(serde_json::json!({"schedule":entry("RUNNING"),"interval":null,"cron":null,"eventTrigger":null}))}})).route("/api/v1/runs/schedule-run",get(||async{Json(json!({"runId":"schedule-run","projectId":"p","sessionId":"session","turnId":null,"status":"QUEUED","failure":null}))})).route("/api/v1/runs/schedule-run/events",get(||async{([("content-type","text/event-stream")],format!("event: agent.run.completed\nid: 1\ndata: {}\n\n",json!({"type":"agent.run.completed","version":1,"sequence":1,"runId":"schedule-run","sessionId":"session","turnId":null,"projectId":"p","payload":{}})))}));
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
    let run = app.schedule_track(&server, "p", "timer").await.unwrap();
    assert_eq!(run.run_id, "schedule-run");
    assert_eq!(run.session_id, "session");
    app.schedule_track(&server, "p", "timer").await.unwrap();
    assert_eq!(app.runs.all().len(), 1);
    assert_eq!(posts.load(Ordering::SeqCst), 0);
    task.abort();
}
