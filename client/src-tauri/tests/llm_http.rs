use axum::{http::HeaderMap, routing::get, Json, Router};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::{json, Value};
fn endpoint() -> Value {
    json!({"profile":{"id":"local","model":"model","provider":"openai-compatible","api":"chat-completions","authentication":"none","accountRef":null,"usagePath":"local-llm","baseUrl":"PRIVATE_URL","apiKey":"PRIVATE_KEY","capabilities":{"route":{"STREAMING":"SUPPORTED"},"server":{"STREAMING":"SUPPORTED"},"model":{"VISION":"UNKNOWN"},"effective":{"VISION":"UNKNOWN"}}},"active":true,"reasoningReplay":"NONE","authenticationState":"not-required","unavailableReason":null,"generationSettings":{"temperature":0.2},"unappliedSettings":{},"diagnostics":[],"selectionScope":"process-wide; next Run only"})
}
async fn serve(router: Router) -> HttpReiClient {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap(); });
    HttpReiClient::new(&url, Some(Secret::new("fixture-server-key".into()))).unwrap()
}
#[tokio::test]
async fn named_metadata_is_authenticated_and_projects_only_public_fields() {
    let api = serve(Router::new().route("/api/v1/llm/chat", get(|headers: HeaderMap| async move {
        assert_eq!(headers["authorization"], "Bearer fixture-server-key");
        Json(json!([endpoint()]))
    }))).await;
    let rows = api.llm_endpoints().await.unwrap();
    assert_eq!(rows[0].profile.id, "local");
    assert!(!serde_json::to_string(&rows).unwrap().contains("PRIVATE_"));
}
#[tokio::test]
async fn writes_use_the_shared_routes_and_bind_model_to_expected_endpoint() {
    let api = serve(Router::new()
        .route("/api/v1/llm/chat/current", axum::routing::put(|Json(body): Json<Value>| async move {
            assert_eq!(body, json!({"endpoint":"local"})); Json(endpoint())
        }))
        .route("/api/v1/models", get(|| async { Json(json!({"endpoint":"local","currentModel":"model","models":["model","second"]})) }))
        .route("/api/v1/models/current", axum::routing::put(|Json(body): Json<Value>| async move {
            assert_eq!(body, json!({"endpoint":"local","model":"second"}));
            Json(json!({"endpoint":"local","model":"second"}))
        }))).await;
    assert_eq!(api.llm_select("local").await.unwrap().profile.id, "local");
    assert_eq!(api.llm_models().await.unwrap().models, vec!["model", "second"]);
    let changed = api.llm_select_model("local", "second").await.unwrap();
    assert_eq!(changed.endpoint, "local");
    assert_eq!(changed.current_model.as_deref(), Some("second"));
}
#[tokio::test]
async fn missing_controls_are_reported_without_inference_or_retry() {
    let api = serve(Router::new()).await;
    assert_eq!(api.llm_endpoints().await.unwrap_err(), AppError::EndpointNotFound);
}
