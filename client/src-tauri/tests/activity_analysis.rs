use axum::{extract::Query, http::HeaderMap, routing::get, Json, Router};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
#[tokio::test]
async fn saved_activity_analysis_uses_authenticated_read_and_retains_report_scope() {
    let app=Router::new().route("/api/v1/activity/period",get(|headers:HeaderMap,Query(query):Query<std::collections::HashMap<String,String>>|async move{assert_eq!(headers["authorization"],"Bearer secret");assert_eq!(query["period"],"WEEK");assert_eq!(query["date"],"2026-09-23");Json(json!({"schemaVersion":1,"scope":"LOCAL_DEVICE_OBSERVATIONS","period":"WEEK","anchorDate":"2026-09-21","zone":"Asia/Tokyo","partial":false,"report":"観測推定値"}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    let result = api
        .workspace(WorkspaceOperation::ActivityAnalysis {
            period: "WEEK".into(),
            date: Some("2026-09-23".into()),
        })
        .await
        .unwrap();
    assert!(result.items[0]
        .fields
        .iter()
        .any(|(k, v)| k == "Report" && v == "観測推定値"));
    assert!(result.items[0]
        .fields
        .iter()
        .any(|(k, v)| k == "Scope" && v == "LOCAL_DEVICE_OBSERVATIONS"));
    task.abort();
}
#[tokio::test]
async fn arbitrary_analysis_period_or_date_cannot_become_an_endpoint() {
    let api = HttpReiClient::new("http://127.0.0.1:1", Some(Secret::new("secret".into()))).unwrap();
    for op in [
        WorkspaceOperation::ActivityAnalysis {
            period: "DAY".into(),
            date: None,
        },
        WorkspaceOperation::ActivityAnalysis {
            period: "WEEK".into(),
            date: Some("../../private".into()),
        },
    ] {
        assert!(matches!(
            api.workspace(op).await,
            Err(AppError::InvalidInput)
        ));
    }
}
#[tokio::test]
async fn a_report_with_an_unknown_scope_is_not_presented_as_device_observations() {
    let app=Router::new().route("/api/v1/activity/period",get(||async{Json(json!({"schemaVersion":1,"scope":"MODEL_CLAIM","period":"WEEK","anchorDate":"2026-09-21","zone":"Asia/Tokyo","partial":false,"report":"untrusted inference"}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert!(matches!(
        api.workspace(WorkspaceOperation::ActivityAnalysis {
            period: "WEEK".into(),
            date: None
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    task.abort();
}
