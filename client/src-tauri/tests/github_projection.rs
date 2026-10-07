use axum::{http::HeaderMap, routing::get, Json, Router};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
#[tokio::test]
async fn github_inbox_and_triggered_task_use_existing_native_owner_projections() {
    let router = Router::new()
        .route("/api/v1/projects/p/attention", get(|headers: HeaderMap| async move {
            assert_eq!(headers["authorization"], "Bearer key");
            Json(json!([{"id":"notice","projectId":"p","sessionId":"session","runId":null,"kind":"GITHUB_REVIEW_SUBMITTED","reference":"fact","message":"GitHub REVIEW_SUBMITTED: owner/repo #17. Inspect the saved fact.","status":"OPEN","createdAt":"2026-10-07T04:00:00Z"}]))
        }))
        .route("/api/v1/tasks", get(|headers: HeaderMap| async move {
            assert_eq!(headers["authorization"], "Bearer key");
            Json(json!({"items":[{"id":"schedule:timer","kind":"SCHEDULE","sourceId":"timer","projectId":"p","sessionId":"session","runId":null,"status":"QUEUED","mode":"EXCLUSIVE","startedAt":"2026-10-07T03:00:00Z","updatedAt":"2026-10-07T04:00:00Z","waitingReason":"due","errorSummary":null,"progress":null,"results":[{"kind":"GITHUB_EVENT","id":"fact"}],"goalId":null,"dependencyIds":[],"schedulerId":"timer","parentId":null,"childIds":[],"checkpointTaskId":null,"cancelSupported":true,"resumeSupported":false,"inputSupported":false,"suspendSupported":false,"revision":3}],"nextCursor":null}))
        }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let server = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("key".into()))).unwrap();
    let inbox = api
        .workspace(WorkspaceOperation::Attention {
            project_id: "p".into(),
        })
        .await
        .unwrap();
    assert_eq!(inbox.items[0].title, "GITHUB_REVIEW_SUBMITTED");
    assert!(inbox.items[0]
        .fields
        .iter()
        .any(|(key, value)| key == "Run" && value.is_empty()));
    let tasks = api
        .list_tasks(
            TaskQuery::new(Some("p".into()), Some("session".into()), Some(10), None).unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(tasks.items[0].scheduler_id.as_deref(), Some("timer"));
    assert_eq!(tasks.items[0].results[0].kind, "GITHUB_EVENT");
    server.abort();
}
