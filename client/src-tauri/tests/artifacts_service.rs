use axum::{routing::get, Json, Router};
use rei_client_lib::{application::*, domain::*, ports::*};
use serde_json::json;
use std::sync::Arc;
struct Sink;
impl RunObserver for Sink {
    fn changed(&self, _: RunView) {}
}
impl NotificationPort for Sink {
    fn notify(&self, _: &RunView) -> Result<()> {
        Ok(())
    }
}
const ID: &str = "00000000-0000-4000-8000-000000000001";
#[tokio::test]
async fn image_preview_returns_only_a_verified_bounded_data_url() {
    use base64::Engine;
    use sha2::{Digest, Sha256};
    let bytes = base64::engine::general_purpose::STANDARD.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aM1sAAAAASUVORK5CYII=").unwrap();
    let digest = format!("{:x}", Sha256::digest(&bytes));
    let length = bytes.len();
    let router = Router::new()
        .route("/api/v1/artifacts/{id}", get(move || { let digest = digest.clone(); async move {
            Json(json!({"artifactId":ID,"owner":"RUN","projectId":"p","sessionId":"s","runId":"r","taskId":null,"mediaType":"image/png","filename":"image.png","size":length,"sha256":digest,"createdAt":"2026-10-07T01:00:00Z","expiresAt":null,"storageReference":format!("artifact:{ID}"),"status":"AVAILABLE"}))
        }}))
        .route("/api/v1/artifacts/{id}/content", get(move || { let bytes = bytes.clone(); async move { ([("content-type","image/png")],bytes) }}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let directory = tempfile::tempdir().unwrap();
    let app =
        Application::open(directory.path().join("app"), Arc::new(Sink), Arc::new(Sink)).unwrap();
    app.unlock(Secret::new("long passphrase".into())).unwrap();
    let server = app.save_server(None, "Server", &url).unwrap();
    app.set_credential(&server, Secret::new("key".into()))
        .unwrap();
    let preview = app
        .artifact_preview(&server, "p", Some("s"), ID)
        .await
        .unwrap();
    assert!(preview.text.is_none());
    assert!(preview
        .data_url
        .unwrap()
        .starts_with("data:image/png;base64,iVBOR"));
    assert!(app.runs.all().is_empty());
}
#[tokio::test]
async fn text_preview_and_explicit_save_use_verified_bytes_without_reexecution_or_overwrite() {
    let router=Router::new().route("/api/v1/artifacts/{id}",get(||async{Json(json!({"artifactId":ID,"owner":"RUN","projectId":"p","sessionId":"s","runId":"r","taskId":null,"mediaType":"text/plain","filename":"結果.txt","size":4,"sha256":"8b3369944dd2a3fab39e32d1aeb1f763946a458ae3e6368a46432adc8f3a0860","createdAt":"2026-10-07T01:00:00Z","expiresAt":"2026-11-06T01:00:00Z","storageReference":format!("artifact:{ID}"),"status":"AVAILABLE"}))}))
        .route("/api/v1/artifacts/{id}/content",get(||async{([("content-type","text/plain")],"safe")}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let directory = tempfile::tempdir().unwrap();
    let app =
        Application::open(directory.path().join("app"), Arc::new(Sink), Arc::new(Sink)).unwrap();
    app.unlock(Secret::new("long passphrase".into())).unwrap();
    let server = app.save_server(None, "Server", &url).unwrap();
    app.set_credential(&server, Secret::new("key".into()))
        .unwrap();
    let preview = app
        .artifact_preview(&server, "p", Some("s"), ID)
        .await
        .unwrap();
    assert_eq!(preview.text.as_deref(), Some("safe"));
    assert!(preview.data_url.is_none());
    let downloads = directory.path().join("downloads");
    let receipt = app
        .artifact_save(&server, "p", Some("s"), ID, &downloads)
        .await
        .unwrap();
    assert_eq!(std::fs::read(&receipt.path).unwrap(), b"safe");
    assert!(std::path::Path::new(&receipt.path).starts_with(&downloads));
    std::fs::write(&receipt.path, b"mine").unwrap();
    assert_eq!(
        app.artifact_save(&server, "p", Some("s"), ID, &downloads)
            .await
            .unwrap_err(),
        AppError::Conflict
    );
    assert_eq!(std::fs::read(&receipt.path).unwrap(), b"mine");
    assert!(app.runs.all().is_empty());
}
