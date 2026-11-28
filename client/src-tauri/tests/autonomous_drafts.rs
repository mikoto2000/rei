use axum::{
    body::to_bytes,
    extract::{Request, State},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};

type Captured = Arc<Mutex<Vec<(String, String, Value)>>>;
#[derive(Clone)]
struct Fixture {
    response: Value,
    captured: Captured,
}

async fn capture(State(fixture): State<Fixture>, request: Request) -> Json<Value> {
    assert_eq!(request.headers()["authorization"], "Bearer fixture-key");
    let method = request.method().to_string();
    let path = request.uri().to_string();
    let body = to_bytes(request.into_body(), 32768).await.unwrap();
    let value = if body.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&body).unwrap()
    };
    fixture.captured.lock().unwrap().push((method, path, value));
    Json(fixture.response)
}

async fn server(response: Value) -> (HttpReiClient, tokio::task::JoinHandle<()>, Captured) {
    let captured = Arc::new(Mutex::new(Vec::new()));
    let app = Router::new().fallback(capture).with_state(Fixture {
        response,
        captured: captured.clone(),
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    (
        HttpReiClient::new(&url, Some(Secret::new("fixture-key".into()))).unwrap(),
        task,
        captured,
    )
}

fn draft(id: &str, revision: i32, text: &str) -> Value {
    json!({"id":id,"actionType":"BLUESKY_POST","opportunityId":"manual:fixture","trigger":"MANUAL",
        "createdAt":"2026-11-28T03:00:00Z","updatedAt":"2026-11-28T03:00:00Z",
        "target":{"did":"did:plc:example","handle":"rei.example"},
        "content":{"revision":revision,"text":text,"bodyHash":"fixture","reason":"公開話題の共有","sourceIds":["topic:source:1"]},
        "state":"PENDING_REVIEW","expiresAt":"2026-12-01T03:00:00Z","rejectionReason":"",
        "rejectionReasonPublic":false,"regenerationAttempts":0,"lastRegenerationAttemptAt":null,"approval":null,"publication":null})
}

fn source(revision: i32, allowed: bool) -> Value {
    json!({"id":"source-one","revision":revision,"text":"公開用の話題","publicUseAllowed":allowed,
        "updatedAt":"2026-11-28T03:00:00Z","actor":"private-operator"})
}
fn rejected() -> Value {
    let mut value = draft("draft-one", 1, "旧本文");
    value["state"] = json!("REJECTED");
    value
}

fn field<'a>(item: &'a WorkspaceItem, name: &str) -> &'a str {
    item.fields
        .iter()
        .find(|(key, _)| key == name)
        .map(|(_, value)| value.as_str())
        .unwrap_or("")
}

#[tokio::test]
async fn selected_publication_and_reconciliation_use_only_the_reviewed_revision() {
    for (action, state) in [
        ("autonomousPublish", "PUBLISHED"),
        ("autonomousPublish", "UNKNOWN"),
        ("autonomousPublish", "FAILED"),
        ("autonomousReconcile", "PUBLISHED"),
        ("autonomousReconcile", "UNKNOWN"),
    ] {
        let mut response = draft("draft-one", 3, "確認した全文\r\n続き");
        response["state"] = json!(state);
        response["publication"] = json!({"uri":null,"cid":null,"result":state,"failureReason":""});
        if state == "PUBLISHED" {
            response["publication"]["uri"] =
                json!("at://did:plc:example/app.bsky.feed.post/3jzfcijpj2z2a");
            response["publication"]["cid"] =
                json!("bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a");
        }
        let (api, task, captured) = server(response).await;
        let op: WorkspaceOperation = serde_json::from_value(json!({"operation":action,
            "id":"draft-one","expectedRevision":3}))
        .unwrap();
        let result = api.workspace(op).await.unwrap();
        assert_eq!(field(&result.items[0], "Status"), state);
        assert_eq!(field(&result.items[0], "Text"), "確認した全文\r\n続き");
        let requests = captured.lock().unwrap();
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].0, "POST");
        assert_eq!(
            requests[0].1,
            format!(
                "/api/v1/autonomous/drafts/draft-one/{}",
                if action == "autonomousPublish" {
                    "publish"
                } else {
                    "reconcile"
                }
            )
        );
        assert_eq!(requests[0].2, json!({"expectedRevision":3}));
        task.abort();
    }
}

#[tokio::test]
async fn publication_responses_must_match_the_selected_revision_and_terminal_state() {
    for (revision, state) in [
        (2, "PUBLISHED"),
        (3, "PENDING_REVIEW"),
        (3, "PUBLISHING"),
        (3, "REJECTED"),
    ] {
        let mut response = draft("draft-one", revision, "本文");
        response["state"] = json!(state);
        let (api, task, captured) = server(response).await;
        let op: WorkspaceOperation =
            serde_json::from_value(json!({"operation":"autonomousPublish",
            "id":"draft-one","expectedRevision":3}))
            .unwrap();
        assert!(matches!(
            api.workspace(op).await,
            Err(AppError::InvalidResponse)
        ));
        assert_eq!(captured.lock().unwrap().len(), 1);
        task.abort();
    }
}

#[tokio::test]
async fn authenticated_draft_detail_preserves_full_text_revision_target_and_expiry() {
    let body = "全文\r\n次の行";
    let (api, task, captured) = server(draft("draft-one", 2, body)).await;
    let result = api
        .workspace(WorkspaceOperation::AutonomousDraft {
            id: "draft-one".into(),
        })
        .await
        .unwrap();
    let item = &result.items[0];
    assert_eq!(item.id.as_deref(), Some("draft-one"));
    assert_eq!(field(item, "Text"), body);
    assert_eq!(field(item, "Revision"), "2");
    assert_eq!(field(item, "Account"), "rei.example");
    assert_eq!(field(item, "DID"), "did:plc:example");
    assert_eq!(field(item, "SourceIds"), "topic:source:1");
    assert_eq!(field(item, "ExpiresAt"), "2026-12-01T03:00:00Z");
    assert_eq!(captured.lock().unwrap()[0].0, "GET");
    assert_eq!(
        captured.lock().unwrap()[0].1,
        "/api/v1/autonomous/drafts/draft-one"
    );
    task.abort();
}

#[tokio::test]
async fn fixed_write_routes_send_only_viewed_revision_and_explicit_values() {
    let cases = vec![
        (
            WorkspaceOperation::AutonomousEdit {
                id: "draft-one".into(),
                expected_revision: 1,
                text: "新しい本文".into(),
            },
            "/api/v1/autonomous/drafts/draft-one/edit",
            json!({"expectedRevision":1,"text":"新しい本文"}),
            draft("draft-one", 2, "新しい本文"),
        ),
        (
            WorkspaceOperation::AutonomousReject {
                id: "draft-one".into(),
                expected_revision: 1,
                reason: "PRIVATE_REASON".into(),
            },
            "/api/v1/autonomous/drafts/draft-one/reject",
            json!({"expectedRevision":1,"reason":"PRIVATE_REASON"}),
            rejected(),
        ),
        (
            WorkspaceOperation::AutonomousAddSource {
                text: "話題".into(),
                public_use_allowed: false,
            },
            "/api/v1/autonomous/sources",
            json!({"text":"話題","publicUseAllowed":false}),
            source(1, false),
        ),
        (
            WorkspaceOperation::AutonomousEditSource {
                id: "source-one".into(),
                expected_revision: 2,
                text: "話題".into(),
            },
            "/api/v1/autonomous/sources/source-one/edit",
            json!({"expectedRevision":2,"text":"話題"}),
            source(3, false),
        ),
        (
            WorkspaceOperation::AutonomousPermitSource {
                id: "source-one".into(),
                expected_revision: 3,
            },
            "/api/v1/autonomous/sources/source-one/permit",
            json!({"expectedRevision":3}),
            source(4, true),
        ),
        (
            WorkspaceOperation::AutonomousRevokeSource {
                id: "source-one".into(),
                expected_revision: 4,
            },
            "/api/v1/autonomous/sources/source-one/revoke",
            json!({"expectedRevision":4}),
            source(5, false),
        ),
    ];
    for (operation, path, body, response) in cases {
        let (api, task, captured) = server(response).await;
        api.workspace(operation).await.unwrap();
        assert_eq!(
            captured.lock().unwrap().as_slice(),
            &[("POST".into(), path.into(), body)]
        );
        task.abort();
    }
}

#[tokio::test]
async fn generation_returns_new_text_and_preserves_failed_or_skipped_outcomes() {
    for status in ["DRAFT_REGENERATED", "SKIPPED", "FAILED"] {
        let response = json!({"status":status,"reason":"generation_failed","draftId":"draft-one","draft":draft("draft-one",2,"表示する全文")});
        let (api, task, captured) = server(response).await;
        let result = api
            .workspace(WorkspaceOperation::AutonomousRegenerate {
                id: "draft-one".into(),
                expected_revision: 1,
            })
            .await
            .unwrap();
        let item = result
            .items
            .iter()
            .find(|i| i.id.as_deref() == Some("draft-one"))
            .unwrap();
        assert_eq!(field(item, "Text"), "表示する全文");
        assert!(result
            .items
            .iter()
            .any(|item| field(item, "GenerationStatus") == status));
        assert_eq!(
            captured.lock().unwrap()[0].1,
            "/api/v1/autonomous/drafts/draft-one/regenerate"
        );
        assert_eq!(captured.lock().unwrap()[0].2, json!({"expectedRevision":1}));
        task.abort();
    }
}

#[tokio::test]
async fn invalid_input_is_rejected_before_any_network_call() {
    let (api, task, captured) = server(Value::Null).await;
    for operation in [
        WorkspaceOperation::AutonomousDrafts { limit: 201 },
        WorkspaceOperation::AutonomousSources { limit: 0 },
        WorkspaceOperation::AutonomousEdit {
            id: "draft-one".into(),
            expected_revision: 0,
            text: "本文".into(),
        },
        WorkspaceOperation::AutonomousRegenerate {
            id: "draft-one".into(),
            expected_revision: -1,
        },
        WorkspaceOperation::AutonomousPermitSource {
            id: "source-one".into(),
            expected_revision: 0,
        },
        WorkspaceOperation::AutonomousDraft { id: "..".into() },
    ] {
        assert!(matches!(
            api.workspace(operation).await,
            Err(AppError::InvalidInput)
        ));
    }
    assert!(captured.lock().unwrap().is_empty());
    task.abort();
}

#[tokio::test]
async fn wrong_resource_or_action_type_is_not_presented_as_the_reviewed_draft() {
    for response in [draft("another-draft", 1, "本文"), {
        let mut value = draft("draft-one", 1, "本文");
        value["actionType"] = json!("OTHER_ACTION");
        value
    }] {
        let (api, task, _) = server(response).await;
        assert!(matches!(
            api.workspace(WorkspaceOperation::AutonomousDraft {
                id: "draft-one".into()
            })
            .await,
            Err(AppError::InvalidResponse)
        ));
        task.abort();
    }
}

#[test]
fn missing_revision_and_extra_authority_fields_are_not_ipc_operations() {
    for input in [
        json!({"operation":"autonomousPublish","id":"draft-one"}),
        json!({"operation":"autonomousPublish","id":"draft-one","expectedRevision":1,"actor":"human"}),
        json!({"operation":"autonomousPublish","id":"draft-one","expectedRevision":1,"text":"差し替え"}),
        json!({"operation":"autonomousReconcile","id":"draft-one","expectedRevision":1,"targetDid":"did:plc:other"}),
        json!({"operation":"autonomousRegenerate","id":"draft-one","expectedRevision":1,"approved":true}),
        json!({"operation":"autonomousEdit","id":"draft-one","expectedRevision":1,"text":"本文","targetDid":"did:plc:other"}),
    ] {
        assert!(serde_json::from_value::<WorkspaceOperation>(input).is_err());
    }
}

#[tokio::test]
async fn publication_result_requires_a_consistent_journal_and_success_receipt() {
    for defect in [
        "missing_journal",
        "wrong_result",
        "missing_uri",
        "missing_cid",
    ] {
        let mut response = draft("draft-one", 1, "全文");
        response["state"] = json!("PUBLISHED");
        response["publication"] = json!({"uri":"at://did:plc:example/app.bsky.feed.post/3jzfcijpj2z2a",
            "cid":"bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a","result":"PUBLISHED"});
        match defect {
            "missing_journal" => response["publication"] = Value::Null,
            "wrong_result" => response["publication"]["result"] = json!("UNKNOWN"),
            "missing_uri" => response["publication"]["uri"] = Value::Null,
            _ => response["publication"]["cid"] = Value::Null,
        }
        let (api, task, _) = server(response).await;
        let op: WorkspaceOperation =
            serde_json::from_value(json!({"operation":"autonomousPublish",
            "id":"draft-one","expectedRevision":1}))
            .unwrap();
        assert!(matches!(
            api.workspace(op).await,
            Err(AppError::InvalidResponse)
        ));
        task.abort();
    }
}

#[tokio::test]
async fn mutation_responses_must_match_the_new_revision_and_outcome() {
    let cases = [
        (
            WorkspaceOperation::AutonomousEdit {
                id: "draft-one".into(),
                expected_revision: 1,
                text: "本文".into(),
            },
            draft("draft-one", 1, "古い本文"),
        ),
        (
            WorkspaceOperation::AutonomousReject {
                id: "draft-one".into(),
                expected_revision: 1,
                reason: "".into(),
            },
            draft("draft-one", 1, "本文"),
        ),
        (
            WorkspaceOperation::AutonomousPermitSource {
                id: "source-one".into(),
                expected_revision: 1,
            },
            source(2, false),
        ),
        (
            WorkspaceOperation::AutonomousEditSource {
                id: "source-one".into(),
                expected_revision: 1,
                text: "話題".into(),
            },
            source(2, true),
        ),
        (
            WorkspaceOperation::AutonomousRegenerate {
                id: "draft-one".into(),
                expected_revision: 1,
            },
            json!({"status":"DRAFT_REGENERATED","reason":"generated","draftId":"draft-one","draft":draft("draft-one",1,"古い本文")}),
        ),
    ];
    for (op, response) in cases {
        let (api, task, _) = server(response).await;
        assert!(matches!(
            api.workspace(op).await,
            Err(AppError::InvalidResponse)
        ));
        task.abort();
    }
}

#[tokio::test]
async fn lists_and_manual_generation_use_fixed_global_routes() {
    for (op, response, path) in [
        (
            WorkspaceOperation::AutonomousDrafts { limit: 20 },
            json!([draft("draft-one", 1, "本文")]),
            "/api/v1/autonomous/drafts?limit=20",
        ),
        (
            WorkspaceOperation::AutonomousSources { limit: 30 },
            json!([source(1, false)]),
            "/api/v1/autonomous/sources?limit=30",
        ),
        (
            WorkspaceOperation::AutonomousGenerate,
            json!({"status":"DRAFT_CREATED","reason":"generated","draftId":"draft-one","draft":draft("draft-one",1,"新しい本文")}),
            "/api/v1/autonomous/drafts/generate",
        ),
        (
            WorkspaceOperation::AutonomousGenerate,
            json!({"status":"SKIPPED","reason":"disabled","draftId":null,"draft":null}),
            "/api/v1/autonomous/drafts/generate",
        ),
    ] {
        let (api, task, captured) = server(response).await;
        api.workspace(op).await.unwrap();
        assert_eq!(captured.lock().unwrap()[0].1, path);
        task.abort();
    }
}
