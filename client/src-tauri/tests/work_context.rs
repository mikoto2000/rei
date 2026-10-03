use axum::{
    http::HeaderMap,
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
#[tokio::test]
async fn native_handoff_operations_use_authenticated_project_reads_and_session_updates() {
    let app=Router::new()
        .route("/api/v1/projects/p/work-context/summary",get(|headers:HeaderMap| async move {
            assert_eq!(headers["authorization"],"Bearer secret");
            Json(json!({"autoPresent":true,"hasContext":true,"text":"pending verification; next check Native"}))
        }))
        .route("/api/v1/projects/p/work-context/history",get(||async {Json(json!([{"revision":1,"updatedAt":"2026-10-03T00:00:00Z","itemCount":2}]))}))
        .route("/api/v1/sessions/session-a/work-context/update",post(|headers:HeaderMap|async move {
            assert_eq!(headers["authorization"],"Bearer secret");Json(json!({"projectId":"p","revision":2,"message":"Saved finalized turns"}))
        }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    let summary = api
        .workspace(WorkspaceOperation::WorkContext {
            project_id: "p".into(),
        })
        .await
        .unwrap();
    assert!(summary.items[0].fields[0]
        .1
        .contains("pending verification"));
    assert_eq!(
        api.workspace(WorkspaceOperation::WorkContextHistory {
            project_id: "p".into()
        })
        .await
        .unwrap()
        .items
        .len(),
        1
    );
    assert_eq!(
        api.workspace(WorkspaceOperation::WorkContextUpdate {
            session_id: "session-a".into()
        })
        .await
        .unwrap()
        .items
        .len(),
        1
    );
    task.abort();
}
