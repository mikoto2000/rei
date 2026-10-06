use axum::{
    extract::{Path, Query},
    http::HeaderMap,
    routing::get,
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
#[tokio::test]
async fn observation_context_read_preserves_project_scope_date_and_missing_metadata() {
    let app=Router::new().route("/api/v1/projects/{project}/activity/observation-context",get(|headers:HeaderMap,Path(project):Path<String>,Query(query):Query<std::collections::HashMap<String,String>>|async move{assert_eq!(project,"p");assert_eq!(headers["authorization"],"Bearer secret");assert_eq!(query["date"],"2026-10-05");Json(json!({"schemaVersion":1,"scope":"PROJECT_OBSERVATION_CONTEXT","projectId":"p","date":"2026-10-05","zone":"Z","partial":true,"missingContextRecords":2,"linkedObservations":1,"report":"保存出典"}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    let result = api
        .workspace(WorkspaceOperation::ActivityObservationContext {
            project_id: "p".into(),
            date: Some("2026-10-05".into()),
        })
        .await
        .unwrap();
    assert!(result.items[0]
        .fields
        .iter()
        .any(|(k, v)| k == "MissingContextRecords" && v == "2"));
    assert!(result.items[0]
        .fields
        .iter()
        .any(|(k, v)| k == "Project" && v == "p"));
    task.abort();
}
#[tokio::test]
async fn invalid_calendar_input_is_rejected_before_network() {
    let api = HttpReiClient::new("http://127.0.0.1:1", Some(Secret::new("secret".into()))).unwrap();
    assert!(matches!(
        api.workspace(WorkspaceOperation::ActivityObservationContext {
            project_id: "p".into(),
            date: Some("../../private".into())
        })
        .await,
        Err(AppError::InvalidInput)
    ));
}
#[tokio::test]
async fn another_projects_report_cannot_be_presented_in_selected_project() {
    let app=Router::new().route("/api/v1/projects/p/activity/observation-context",get(||async{Json(json!({"schemaVersion":1,"scope":"PROJECT_OBSERVATION_CONTEXT","projectId":"other","date":"2026-10-05","zone":"Z","partial":false,"missingContextRecords":0,"linkedObservations":1,"report":"private"}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert!(matches!(
        api.workspace(WorkspaceOperation::ActivityObservationContext {
            project_id: "p".into(),
            date: None
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    task.abort();
}
#[tokio::test]
async fn unknown_scope_wrong_date_or_oversized_link_count_is_rejected() {
    for mode in 0..3 {
        let mut value = json!({"schemaVersion":1,"scope":"PROJECT_OBSERVATION_CONTEXT","projectId":"p","date":"2026-10-05","zone":"Z","partial":false,"missingContextRecords":0,"linkedObservations":1,"report":"saved"});
        match mode {
            0 => value["scope"] = json!("MODEL_CLAIM"),
            1 => value["date"] = json!("2026-10-06"),
            _ => value["linkedObservations"] = json!(129),
        };
        let app = Router::new().route(
            "/api/v1/projects/p/activity/observation-context",
            get(move || {
                let value = value.clone();
                async move { Json(value) }
            }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
        assert!(matches!(
            api.workspace(WorkspaceOperation::ActivityObservationContext {
                project_id: "p".into(),
                date: Some("2026-10-05".into())
            })
            .await,
            Err(AppError::InvalidResponse)
        ));
        task.abort();
    }
}
