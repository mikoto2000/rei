use axum::{
    http::HeaderMap,
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::{json, Value};
fn settings() -> ActivityCoachingSettings {
    serde_json::from_value(json!({"enabled":false,"categories":["development"],"targetShare":0.6,"minimumObservedMinutes":120,"minimumCoverage":0.1,"maximumUnknownShare":0.25,"cooldownDays":7})).unwrap()
}
fn state(revision: i64, enabled: bool) -> Value {
    let mut settings = serde_json::to_value(settings()).unwrap();
    settings["enabled"] = json!(enabled);
    json!({"schemaVersion":1,"scope":"LOCAL_DEVICE_COACHING_SETTINGS","revision":revision,"settings":settings})
}
async fn server(app: Router) -> (HttpReiClient, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    (
        HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap(),
        task,
    )
}
#[tokio::test]
async fn coaching_settings_get_is_authenticated_and_retains_exact_scope_revision() {
    let (api, task) = server(Router::new().route(
        "/api/v1/activity/coaching",
        get(|headers: HeaderMap| async move {
            assert_eq!(headers["authorization"], "Bearer secret");
            Json(state(4, false))
        }),
    ))
    .await;
    let result = api
        .workspace(WorkspaceOperation::ActivityCoachingSettings)
        .await
        .unwrap();
    assert!(result.items[0]
        .fields
        .iter()
        .any(|(k, v)| k == "Revision" && v == "4"));
    assert!(result.items[0]
        .fields
        .iter()
        .any(|(k, v)| k == "Scope" && v == "LOCAL_DEVICE_COACHING_SETTINGS"));
    task.abort();
}
#[tokio::test]
async fn coaching_settings_write_sends_viewed_revision_and_explicit_enable() {
    let (api, task) = server(
        Router::new()
            .route(
                "/api/v1/activity/coaching/settings",
                post(|headers: HeaderMap, Json(body): Json<Value>| async move {
                    assert_eq!(headers["authorization"], "Bearer secret");
                    assert_eq!(body["expectedRevision"], 4);
                    assert_eq!(body["settings"]["enabled"], false);
                    Json(state(5, false))
                }),
            )
            .route(
                "/api/v1/activity/coaching/enabled",
                post(|Json(body): Json<Value>| async move {
                    assert_eq!(body, json!({"expectedRevision":5,"enabled":true}));
                    Json(state(6, true))
                }),
            ),
    )
    .await;
    api.workspace(WorkspaceOperation::ActivityCoachingConfigure {
        expected_revision: 4,
        settings: settings(),
    })
    .await
    .unwrap();
    api.workspace(WorkspaceOperation::ActivityCoachingEnabled {
        expected_revision: 5,
        enabled: true,
    })
    .await
    .unwrap();
    task.abort();
}
#[tokio::test]
async fn invalid_settings_are_rejected_before_a_network_request() {
    let api = HttpReiClient::new("http://127.0.0.1:1", Some(Secret::new("secret".into()))).unwrap();
    let mut bad = settings();
    bad.categories = vec!["unknown".into()];
    let mut enabled = settings();
    enabled.enabled = true;
    for op in [
        WorkspaceOperation::ActivityCoachingConfigure {
            expected_revision: 0,
            settings: bad,
        },
        WorkspaceOperation::ActivityCoachingConfigure {
            expected_revision: 0,
            settings: enabled,
        },
        WorkspaceOperation::ActivityCoachingEnabled {
            expected_revision: -1,
            enabled: true,
        },
    ] {
        assert!(matches!(
            api.workspace(op).await,
            Err(AppError::InvalidInput)
        ));
    }
}
#[tokio::test]
async fn wrong_scope_or_write_revision_is_not_accepted_as_saved_settings() {
    let (api, task) = server(
        Router::new()
            .route(
                "/api/v1/activity/coaching",
                get(|| async {
                    let mut value = state(1, false);
                    value["scope"] = json!("PROJECT");
                    Json(value)
                }),
            )
            .route(
                "/api/v1/activity/coaching/enabled",
                post(|| async { Json(state(5, true)) }),
            ),
    )
    .await;
    assert!(matches!(
        api.workspace(WorkspaceOperation::ActivityCoachingSettings)
            .await,
        Err(AppError::InvalidResponse)
    ));
    assert!(matches!(
        api.workspace(WorkspaceOperation::ActivityCoachingEnabled {
            expected_revision: 1,
            enabled: true
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    task.abort();
}
#[tokio::test]
async fn malformed_criteria_and_a_mismatching_saved_echo_cannot_be_accepted() {
    for mode in 0..3 {
        let mut value = state(1, false);
        match mode {
            0 => value["settings"]["categories"] = json!(["unknown"]),
            1 => value["schemaVersion"] = json!(2),
            _ => value["revision"] = json!(9_007_199_254_740_992i64),
        };
        let (api, task) = server(Router::new().route(
            "/api/v1/activity/coaching",
            get(move || {
                let value = value.clone();
                async move { Json(value) }
            }),
        ))
        .await;
        assert!(matches!(
            api.workspace(WorkspaceOperation::ActivityCoachingSettings)
                .await,
            Err(AppError::InvalidResponse)
        ));
        task.abort();
    }
    let mut value = state(5, false);
    value["settings"]["targetShare"] = json!(0.8);
    let (api, task) = server(Router::new().route(
        "/api/v1/activity/coaching/settings",
        post(move || {
            let value = value.clone();
            async move { Json(value) }
        }),
    ))
    .await;
    assert!(matches!(
        api.workspace(WorkspaceOperation::ActivityCoachingConfigure {
            expected_revision: 4,
            settings: settings()
        })
        .await,
        Err(AppError::InvalidResponse)
    ));
    task.abort();
}
