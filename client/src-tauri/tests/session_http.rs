use axum::{
    extract::{Path, Query},
    http::{HeaderMap, StatusCode},
    routing::get,
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
use std::collections::HashMap;

fn session(id: &str) -> serde_json::Value {
    json!({"sessionId":id,"projectId":"p","title":"別端末 😀","createdAt":"2026-09-16T08:00:00Z","updatedAt":"2026-09-16T09:00:00Z"})
}
async fn serve(router: Router) -> HttpReiClient {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    HttpReiClient::new(&url, Some(Secret::new("private-key".into()))).unwrap()
}
#[tokio::test]
async fn session_pages_preserve_opaque_cursor_filter_and_authentication() {
    let api = serve(Router::new().route("/api/v1/sessions", get(|headers: HeaderMap, Query(q): Query<HashMap<String,String>>| async move {
        assert_eq!(headers["authorization"], "Bearer private-key");
        assert_eq!(q["limit"], "50"); assert_eq!(q["projectId"], "project name/日本語");
        if let Some(cursor) = q.get("cursor") {
            assert_eq!(cursor, "opaque:+/?=&😀"); Json(json!({"items":[],"nextCursor":null}))
        } else { Json(json!({"items":[session("project:p:chat:s")],"nextCursor":"opaque:+/?=&😀"})) }
    }))).await;
    let first = api
        .list_sessions(
            Some("project name/日本語"),
            HistoryQuery::new(None, None).unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(first.items[0].title, "別端末 😀");
    let next = api
        .list_sessions(
            Some("project name/日本語"),
            HistoryQuery::new(None, first.next_cursor).unwrap(),
        )
        .await
        .unwrap();
    assert!(next.items.is_empty());
    assert!(next.next_cursor.is_none());
}
#[tokio::test]
async fn detail_and_turn_pages_escape_ids_and_validate_identity() {
    let id = "project:p:chat:s?&日本語";
    let api = serve(Router::new()
        .route("/api/v1/sessions/{id}", get(|Path(id): Path<String>| async move { Json(session(&id)) }))
        .route("/api/v1/sessions/{id}/turns", get(|Path(id): Path<String>, Query(q): Query<HashMap<String,String>>| async move {
            assert_eq!(q["limit"], "1");
            Json(json!({"sessionId":id,"items":[{"turnId":"run","runId":"run","userMessage":"q","assistantMessage":null,"createdAt":"2026-09-16T08:00:00Z"}],"nextCursor":"next"}))
        }))).await;
    assert_eq!(api.get_session(id).await.unwrap().session_id, id);
    let page = api
        .list_session_turns(id, HistoryQuery::new(Some(1), None).unwrap())
        .await
        .unwrap();
    assert_eq!(page.items[0].run_id, "run");
    assert_eq!(page.items[0].assistant_message, None);
    assert_eq!(page.next_cursor.as_deref(), Some("next"));
}
#[tokio::test]
async fn status_and_malformed_responses_are_safe_application_errors() {
    for (status, expected) in [
        (401, AppError::AuthenticationFailed),
        (400, AppError::InvalidCursor),
        (500, AppError::UnexpectedServerError),
    ] {
        let api = serve(Router::new().route(
            "/api/v1/sessions",
            get(move || async move { (StatusCode::from_u16(status).unwrap(), "private-key") }),
        ))
        .await;
        assert_eq!(
            api.list_sessions(None, HistoryQuery::new(None, Some("bad".into())).unwrap())
                .await
                .unwrap_err(),
            expected
        );
    }
    let api = serve(
        Router::new()
            .route(
                "/api/v1/sessions/{id}",
                get(|| async { StatusCode::NOT_FOUND }),
            )
            .route(
                "/api/v1/sessions/{id}/turns",
                get(|| async { StatusCode::NOT_FOUND }),
            )
            .route(
                "/api/v1/sessions",
                get(|| async { Json(json!({"items":[{}],"nextCursor":null})) }),
            ),
    )
    .await;
    assert_eq!(
        api.get_session("missing").await.unwrap_err(),
        AppError::SessionNotFound
    );
    assert_eq!(
        api.list_session_turns("missing", HistoryQuery::new(None, None).unwrap())
            .await
            .unwrap_err(),
        AppError::SessionNotFound
    );
    assert_eq!(
        api.list_sessions(None, HistoryQuery::new(None, None).unwrap())
            .await
            .unwrap_err(),
        AppError::InvalidResponse
    );
}
#[tokio::test]
async fn invalid_turn_identity_timestamp_and_unreachable_server_are_rejected() {
    let api = serve(Router::new().route("/api/v1/sessions/{id}/turns", get(|| async {
        Json(json!({"sessionId":"s","items":[{"turnId":"wrong","runId":"run","userMessage":"q","assistantMessage":"a","createdAt":"bad"}],"nextCursor":null}))
    }))).await;
    assert_eq!(
        api.list_session_turns("s", HistoryQuery::new(None, None).unwrap())
            .await
            .unwrap_err(),
        AppError::InvalidResponse
    );
    let api = HttpReiClient::new("http://127.0.0.1:1", Some(Secret::new("key".into()))).unwrap();
    assert_eq!(
        api.list_sessions(None, HistoryQuery::new(None, None).unwrap())
            .await
            .unwrap_err(),
        AppError::ServerUnreachable
    );
}
