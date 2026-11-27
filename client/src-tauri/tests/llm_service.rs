use axum::{http::HeaderMap, routing::get, Json, Router};
use rei_client_lib::{application::*, domain::*, ports::*};
use serde_json::json;
use std::sync::Arc;
struct Sink;
impl RunObserver for Sink { fn changed(&self, _: RunView) {} }
impl NotificationPort for Sink { fn notify(&self, _: &RunView) -> Result<()> { Ok(()) } }
#[tokio::test]
async fn metadata_uses_the_requested_conversation_server_without_a_run_or_persistence() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let router = Router::new().route("/api/v1/llm/chat", get(|headers: HeaderMap| async move {
        assert_eq!(headers["authorization"], "Bearer conversation-server-key");
        Json(json!([{"profile":{"id":"local","model":"fixture","provider":"openai-compatible","api":"chat-completions","authentication":"none","accountRef":null,"usagePath":"local-llm","capabilities":{"route":{},"server":{},"model":{},"effective":{}}},"active":true,"reasoningReplay":"NONE","authenticationState":"not-required","unavailableReason":null,"generationSettings":{},"unappliedSettings":{},"diagnostics":[],"selectionScope":"process-wide; next Run only"}]))
    }));
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap(); });
    let directory = tempfile::tempdir().unwrap();
    let app = Application::open(directory.path().into(), Arc::new(Sink), Arc::new(Sink)).unwrap();
    app.unlock(Secret::new("fixture long passphrase".into())).unwrap();
    let conversation_server = app.save_server(None, "Conversation", &url).unwrap();
    app.set_credential(&conversation_server, Secret::new("conversation-server-key".into())).unwrap();
    let unrelated = app.save_server(None, "Settings", "https://unrelated.invalid").unwrap();
    app.select_server(&unrelated).unwrap();
    assert_eq!(app.llm_endpoints(&conversation_server).await.unwrap()[0].profile.id, "local");
    assert!(app.runs.all().is_empty());
}
