use axum::{
    extract::{Path, Query},
    http::{HeaderMap, StatusCode},
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::{json, Value};
use std::collections::HashMap;
fn task(id: &str, project: &str, session: &str) -> Value {
    json!({"id":id,"kind":"RUN","sourceId":"r","projectId":project,"sessionId":session,"runId":"r","status":"UNKNOWN","mode":"READ_ONLY","startedAt":"2026-10-07T01:00:00Z","updatedAt":"2026-10-07T01:05:00Z","waitingReason":null,"errorSummary":"owner lost","progress":null,"results":[{"kind":"TURN","id":"r"}],"goalId":null,"dependencyIds":[],"schedulerId":null,"parentId":null,"childIds":[],"checkpointTaskId":null,"cancelSupported":false,"resumeSupported":false,"inputSupported":false,"revision":0})
}
async fn serve(router: Router) -> HttpReiClient {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    HttpReiClient::new(&url, Some(Secret::new("key".into()))).unwrap()
}
#[tokio::test]
async fn task_pages_preserve_modes_and_encode_owner_filters_and_cursor() {
    let api = serve(Router::new().route(
        "/api/v1/tasks",
        get(
            |headers: HeaderMap, Query(q): Query<HashMap<String, String>>| async move {
                assert_eq!(headers["authorization"], "Bearer key");
                assert_eq!(q["projectId"], "p");
                assert_eq!(q["sessionId"], "日本語?&s");
                assert_eq!(q["limit"], "1");
                assert_eq!(q["cursor"], "cursor?&value");
                Json(json!({"items":[task("run:r","p","日本語?&s")],"nextCursor":"next"}))
            },
        ),
    ))
    .await;
    let page = api
        .list_tasks(
            TaskQuery::new(
                Some("p".into()),
                Some("日本語?&s".into()),
                Some(1),
                Some("cursor?&value".into()),
            )
            .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(page.items[0].mode, Some(RunMode::ReadOnly));
    assert_eq!(page.items[0].status, "UNKNOWN");
    assert_eq!(page.next_cursor.as_deref(), Some("next"));
}
#[tokio::test]
async fn task_detail_rejects_wrong_project_session_or_identity() {
    let api = serve(Router::new().route(
        "/api/v1/tasks/{id}",
        get(|Path(id): Path<String>| async move { Json(task(&id, "other", "s")) }),
    ))
    .await;
    assert_eq!(
        api.get_task("p", Some("s"), "run:r").await.unwrap_err(),
        AppError::InvalidResponse
    );
    assert_eq!(
        api.get_task("p", Some("s"), "../outside")
            .await
            .unwrap_err(),
        AppError::InvalidInput
    );
}
#[tokio::test]
async fn task_control_sends_exact_owner_run_and_revision_without_chat_fallback() {
    let api=serve(Router::new().route("/api/v1/tasks/{id}/cancel",post(|Path(id):Path<String>,Json(body):Json<Value>|async move {
        assert_eq!(id,"run:r");assert_eq!(body,json!({"projectId":"p","sessionId":"s","expectedRunId":"r","expectedRevision":7}));
        (StatusCode::ACCEPTED,Json(task(&id,"p","s")))
    }))).await;
    let receipt = api
        .control_task(
            "p",
            Some("s"),
            "run:r",
            Some("r"),
            7,
            TaskAction::Cancel,
            None,
        )
        .await
        .unwrap();
    assert_eq!(receipt.id, "run:r");
}
#[tokio::test]
async fn task_suspend_uses_the_checkpoint_control_endpoint_and_preserves_capability() {
    let api = serve(Router::new().route(
        "/api/v1/tasks/{id}/suspend",
        post(
            |Path(id): Path<String>, Json(body): Json<Value>| async move {
                assert_eq!(id, "run:r");
                assert_eq!(body["expectedRevision"], 7);
                let mut receipt = task(&id, "p", "s");
                receipt["kind"] = json!("CHECKPOINT");
                receipt["status"] = json!("CANCELLED");
                receipt["resumeSupported"] = json!(true);
                receipt["suspendSupported"] = json!(false);
                (StatusCode::ACCEPTED, Json(receipt))
            },
        ),
    ))
    .await;
    let receipt = api
        .control_task(
            "p",
            Some("s"),
            "run:r",
            Some("r"),
            7,
            TaskAction::Suspend,
            None,
        )
        .await
        .unwrap();
    assert!(receipt.resume_supported);
    assert!(!receipt.suspend_supported);
}
