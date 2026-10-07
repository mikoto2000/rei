use axum::{
    extract::Query,
    http::{HeaderMap, StatusCode},
    routing::get,
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::{json, Value};
use std::collections::HashMap;
const ID: &str = "00000000-0000-4000-8000-000000000001";
fn receipt() -> Value {
    json!({"artifactId":ID,"owner":"RUN","projectId":"p","sessionId":"日本語?&s","runId":"r","taskId":null,"mediaType":"text/plain","filename":"結果.txt","size":4,"sha256":"8b3369944dd2a3fab39e32d1aeb1f763946a458ae3e6368a46432adc8f3a0860","createdAt":"2026-10-07T01:00:00Z","expiresAt":"2026-11-06T01:00:00Z","storageReference":format!("artifact:{ID}"),"status":"AVAILABLE"})
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
async fn delivery_checks_bearer_owner_encoded_filters_and_content_hash() {
    let api = serve(
        Router::new()
            .route(
                "/api/v1/artifacts",
                get(
                    |headers: HeaderMap, Query(query): Query<HashMap<String, String>>| async move {
                        assert_eq!(headers["authorization"], "Bearer key");
                        assert_eq!(query["sessionId"], "日本語?&s");
                        Json(json!({"items":[receipt()],"nextCursor":null}))
                    },
                ),
            )
            .route(
                "/api/v1/artifacts/{id}/content",
                get(
                    |headers: HeaderMap, Query(query): Query<HashMap<String, String>>| async move {
                        assert_eq!(headers["authorization"], "Bearer key");
                        assert_eq!(query["projectId"], "p");
                        assert_eq!(query["sessionId"], "日本語?&s");
                        (StatusCode::OK, [("content-type", "text/plain")], "safe")
                    },
                ),
            ),
    )
    .await;
    let page = api
        .list_artifacts(
            ArtifactQuery::new(
                Some("p".into()),
                Some("日本語?&s".into()),
                None,
                Some(1),
                None,
            )
            .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(page.items[0].filename, "結果.txt");
    assert_eq!(api.artifact_content(&page.items[0]).await.unwrap(), b"safe");
}
#[tokio::test]
async fn corrupted_delivery_and_wrong_owner_are_not_exposed_as_preview_bytes() {
    let api = serve(
        Router::new()
            .route(
                "/api/v1/artifacts/{id}",
                get(|| async {
                    let mut item = receipt();
                    item["projectId"] = json!("other");
                    Json(item)
                }),
            )
            .route(
                "/api/v1/artifacts/{id}/content",
                get(|| async { ([("content-type", "text/plain")], "evil") }),
            ),
    )
    .await;
    let item: DeliveryArtifact = serde_json::from_value(receipt()).unwrap();
    assert_eq!(
        api.artifact_content(&item).await.unwrap_err(),
        AppError::InvalidResponse
    );
    assert_eq!(
        api.get_artifact("p", Some("日本語?&s"), ID)
            .await
            .unwrap_err(),
        AppError::InvalidResponse
    );
    assert_eq!(
        api.get_artifact("p", Some("日本語?&s"), "../outside")
            .await
            .unwrap_err(),
        AppError::InvalidInput
    );
}
