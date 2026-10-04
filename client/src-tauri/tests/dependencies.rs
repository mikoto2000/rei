use axum::{
    http::HeaderMap,
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
#[tokio::test]
async fn foreign_questions_and_unconfirmed_receipts_fail_without_retry() {
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    };
    let count = Arc::new(AtomicUsize::new(0));
    let posted = count.clone();
    let row = json!({"id":"dep","projectId":"other","sessionId":"session","spec":{"kind":"USER_ANSWER","target":"Question"},"state":"WAITING","reason":"watch_pending","version":3,"answer":null,"deadline":"2026-10-05T00:00:00Z","prerequisites":[]});
    let foreign = row.clone();
    let app = Router::new()
        .route(
            "/api/v1/projects/p/dependencies",
            get(move || async move { Json(json!([foreign])) }),
        )
        .route(
            "/api/v1/projects/p/dependencies/dep/answer",
            post(move || {
                let row = row.clone();
                let posted = posted.clone();
                async move {
                    posted.fetch_add(1, Ordering::SeqCst);
                    Json(row)
                }
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert!(matches!(
        api.workspace(WorkspaceOperation::Dependencies {
            project_id: "p".into()
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    assert!(matches!(
        api.workspace(WorkspaceOperation::DependencyAnswer {
            project_id: "p".into(),
            id: "dep".into(),
            expected_version: 3,
            answer: "A".into()
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    assert_eq!(count.load(Ordering::SeqCst), 1);
    task.abort();
}
#[tokio::test]
async fn saved_questions_and_explicit_versioned_answers_use_authenticated_project_scope() {
    let saved = json!({"id":"dep","projectId":"p","sessionId":"session","spec":{"kind":"USER_ANSWER","target":"Choose A or B","expected":null},"state":"WAITING","reason":"user_answer_waiting","version":3,"answer":null,"deadline":"2026-10-05T00:00:00Z","prerequisites":[]});
    let listed = saved.clone();
    let mut answered = saved;
    answered["version"] = json!(4);
    answered["answer"] = json!("A");
    let app = Router::new()
        .route(
            "/api/v1/projects/p/dependencies",
            get(move |headers: HeaderMap| async move {
                assert_eq!(headers["authorization"], "Bearer secret");
                Json(json!([listed]))
            }),
        )
        .route(
            "/api/v1/projects/p/dependencies/dep/answer",
            post(
                move |headers: HeaderMap, Json(body): Json<serde_json::Value>| async move {
                    assert_eq!(headers["authorization"], "Bearer secret");
                    assert_eq!(body, json!({"expectedVersion":3,"answer":"A"}));
                    Json(answered)
                },
            ),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    let rows = api
        .workspace(WorkspaceOperation::Dependencies {
            project_id: "p".into(),
        })
        .await
        .unwrap();
    assert_eq!(rows.items[0].title, "Choose A or B");
    let result = api
        .workspace(WorkspaceOperation::DependencyAnswer {
            project_id: "p".into(),
            id: "dep".into(),
            expected_version: 3,
            answer: "A".into(),
        })
        .await
        .unwrap();
    assert!(result.items[0]
        .fields
        .contains(&("Answer".into(), "A".into())));
    task.abort();
}
