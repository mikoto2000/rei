use axum::{
    http::HeaderMap,
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
#[tokio::test]
async fn attention_and_approval_use_authenticated_project_scoped_endpoints() {
    let attention = json!({"id":"notice","projectId":"p","sessionId":"session","runId":null,"kind":"DECISION_REQUIRED","reference":"dependency","message":"Review question","status":"OPEN","createdAt":"2026-10-04T00:00:00Z"});
    let approval = json!({"id":"request","projectId":"p","sessionId":"session","runId":"run","tool":"writeFile","argumentsPreview":"redacted","status":"PENDING","expiresAt":"2026-10-04T01:00:00Z"});
    let list_attention = attention.clone();
    let list_approval = approval.clone();
    let mut acknowledged = attention.clone();
    acknowledged["status"] = json!("ACKNOWLEDGED");
    let mut denied = approval.clone();
    denied["status"] = json!("DENIED");
    let app = Router::new()
        .route(
            "/api/v1/projects/p/attention",
            get(move |headers: HeaderMap| async move {
                assert_eq!(headers["authorization"], "Bearer secret");
                Json(json!([list_attention]))
            }),
        )
        .route(
            "/api/v1/projects/p/attention/notice/ack",
            post(move || async move { Json(acknowledged) }),
        )
        .route(
            "/api/v1/projects/p/approvals",
            get(move || async move { Json(json!([list_approval])) }),
        )
        .route(
            "/api/v1/projects/p/approvals/request/decision",
            post(
                move |headers: HeaderMap, Json(body): Json<serde_json::Value>| async move {
                    assert_eq!(headers["authorization"], "Bearer secret");
                    assert_eq!(body, json!({"approved":false}));
                    Json(denied)
                },
            ),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert_eq!(
        api.workspace(WorkspaceOperation::Attention {
            project_id: "p".into()
        })
        .await
        .unwrap()
        .items[0]
            .title,
        "DECISION_REQUIRED"
    );
    assert_eq!(
        api.workspace(WorkspaceOperation::AttentionAck {
            project_id: "p".into(),
            id: "notice".into()
        })
        .await
        .unwrap()
        .items
        .len(),
        1
    );
    assert_eq!(
        api.workspace(WorkspaceOperation::Approvals {
            project_id: "p".into()
        })
        .await
        .unwrap()
        .items[0]
            .title,
        "writeFile"
    );
    assert_eq!(
        api.workspace(WorkspaceOperation::ApprovalDecision {
            project_id: "p".into(),
            id: "request".into(),
            approved: false
        })
        .await
        .unwrap()
        .items
        .len(),
        1
    );
    task.abort();
}

#[tokio::test]
async fn cross_project_dtos_and_unconfirmed_mutation_receipts_are_rejected() {
    let foreign = json!({"id":"notice","projectId":"other","sessionId":"session","kind":"RUN_FAILED","reference":"run","message":"other-project","status":"OPEN","createdAt":"now"});
    let app=Router::new().route("/api/v1/projects/p/attention",get(move||async move{Json(json!([foreign]))}))
 .route("/api/v1/projects/p/attention/notice/ack",post(||async{Json(json!({"id":"notice","projectId":"p","sessionId":"session","kind":"RUN_FAILED","reference":"run","message":"not confirmed","status":"OPEN","createdAt":"now"}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert!(matches!(
        api.workspace(WorkspaceOperation::Attention {
            project_id: "p".into()
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    assert!(matches!(
        api.workspace(WorkspaceOperation::AttentionAck {
            project_id: "p".into(),
            id: "notice".into()
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    let missing = HttpReiClient::new(&url, None).unwrap();
    assert!(matches!(
        missing
            .workspace(WorkspaceOperation::Attention {
                project_id: "p".into()
            })
            .await,
        Err(AppError::AuthenticationFailed) | Err(AppError::InvalidCredential)
    ));
    assert!(matches!(
        api.workspace(WorkspaceOperation::Attention {
            project_id: "..".into()
        })
        .await,
        Err(AppError::InvalidInput)
    ));
    task.abort();
}
