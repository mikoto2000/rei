use axum::{
    http::{HeaderMap, StatusCode},
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, domain::*, ports::ReiClient};
use serde_json::json;
#[tokio::test]
async fn application_submits_background_without_session_history_or_local_conversations() {
    use rei_client_lib::application::*;
    use std::{sync::Arc, time::Duration};
    let app=Router::new().route("/api/v1/projects",get(||async {Json(json!([{"id":"p","name":"rei","path":"/server"}]))}))
        .route("/api/v1/summaries",post(||async {(StatusCode::ACCEPTED,[("location","/api/v1/runs/r")],Json(json!({"runId":"r"})))}))
        .route("/api/v1/runs/r/events",get(||async { ([("content-type","text/event-stream")],format!("event: agent.run.completed\nid: 1\ndata: {}\n\n",json!({"type":"agent.run.completed","version":1,"sequence":1,"runId":"r","sessionId":null,"turnId":null,"projectId":"p","payload":{}}))) }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let dir = tempfile::tempdir().unwrap();
    let app = Application::open(dir.path().into(), Arc::new(Observer), Arc::new(Observer)).unwrap();
    app.unlock(Secret::new("a long passphrase".into())).unwrap();
    let server = app.save_server(None, "Home", &url).unwrap();
    app.set_credential(&server, Secret::new("secret".into()))
        .unwrap();
    assert!(matches!(
        app.background(
            &server,
            "missing",
            BackgroundOperation::Summary {
                url: "https://example.com".into()
            }
        )
        .await,
        Err(AppError::ProjectNotFound)
    ));
    let run = app
        .background(
            &server,
            "p",
            BackgroundOperation::Summary {
                url: "https://example.com".into(),
            },
        )
        .await
        .unwrap();
    assert_eq!(run.run_id, "r");
    assert!(app.conversations.list().is_empty());
    tokio::time::timeout(Duration::from_secs(2), async {
        while !app.runs.get(&server, "r").unwrap().status.terminal() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(
        app.runs.get(&server, "r").unwrap().status,
        RunStatus::Completed
    );
    task.abort();
}
#[tokio::test]
async fn read_contract_rejects_wrong_status_and_content_type() {
    for (status, content_type) in [
        (StatusCode::CREATED, "application/json"),
        (StatusCode::OK, "text/plain"),
    ] {
        let app = Router::new()
            .fallback(move || async move { (status, [("content-type", content_type)], "[]") });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        let api = HttpReiClient::new(&url, Some(Secret::new("key".into()))).unwrap();
        assert_eq!(
            api.workspace(WorkspaceOperation::Feeds).await.unwrap_err(),
            AppError::InvalidResponse
        );
        task.abort();
    }
}
#[test]
fn background_ui_metadata_is_null_and_inconsistent_terminal_ownership_is_rejected() {
    use rei_client_lib::application::*;
    let mut p = Projection::background("server", "p", "r", "summary");
    let value = serde_json::to_value(RunView::from(&p)).unwrap();
    assert!(value["sessionId"].is_null());
    assert!(value["turnId"].is_null());
    assert!(p
        .recover(RunSnapshot {
            run_id: "r".into(),
            project_id: "p".into(),
            session_id: "other".into(),
            turn_id: "".into(),
            status: RunStatus::Completed,
            failure: None
        })
        .is_err());
}

#[tokio::test]
async fn background_stream_reconnect_uses_last_accepted_sequence_and_gap_switches_to_status() {
    use rei_client_lib::application::*;
    use std::{
        sync::{
            atomic::{AtomicUsize, Ordering},
            Arc,
        },
        time::Duration,
    };
    for gap in [false, true] {
        let count = Arc::new(AtomicUsize::new(0));
        let calls = count.clone();
        let frame = |kind: &str, id: u64, payload: serde_json::Value| {
            format!(
                "event: {kind}\nid: {id}\ndata: {}\n\n",
                json!({"type":kind,"sequence":id,"version":1,"runId":"r","sessionId":null,"turnId":null,"projectId":"p","payload":payload})
            )
        };
        let first = format!(
            "{}{}event: heartbeat\ndata: {{}}\n\n",
            frame("agent.run.started", 10, json!({})),
            frame(
                "message.delta",
                12,
                json!({"messageId":"m","delta":"hello"})
            )
        );
        let last = format!(
            "{}{}",
            frame(
                "message.completed",
                18,
                json!({"messageId":"m","text":"hello world","role":"assistant"})
            ),
            frame("agent.run.completed", 20, json!({}))
        );
        let app=Router::new().route("/api/v1/runs/r/events",get(move |h:HeaderMap| {
            let calls=calls.clone();let first=first.clone();let last=last.clone();async move {
                let n=calls.fetch_add(1,Ordering::SeqCst);
                if n==0 {assert!(h.get("last-event-id").is_none());(StatusCode::OK,[("content-type","text/event-stream")],first)}
                else {assert_eq!(h["last-event-id"],"12");if gap {(StatusCode::CONFLICT,[("content-type","application/json")],"{}".into())}else{(StatusCode::OK,[("content-type","text/event-stream")],last)}}
            }
        })).route("/api/v1/runs/r",get(||async {Json(json!({"runId":"r","sessionId":null,"turnId":null,"projectId":"p","status":"COMPLETED","failure":null}))}));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        let api = Arc::new(HttpReiClient::new(&url, Some(Secret::new("key".into()))).unwrap());
        let manager = Arc::new(RunManager::new(
            Arc::new(Observer),
            Arc::new(Observer),
            Duration::from_millis(1),
        ));
        manager
            .register(Projection::background("server", "p", "r", "summary"))
            .unwrap();
        manager.subscribe("server", "r", api.clone()).unwrap();
        let view = tokio::time::timeout(Duration::from_secs(2), async {
            loop {
                let v = manager.get("server", "r").unwrap();
                if v.status.terminal() {
                    break v;
                }
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
        })
        .await
        .unwrap();
        assert_eq!(view.status, RunStatus::Completed);
        assert_eq!(view.incomplete, gap);
        assert_eq!(
            view.assistant_text,
            if gap { "hello" } else { "hello world" }
        );
        assert_eq!(count.load(Ordering::SeqCst), 2);
        manager.subscribe("server", "r", api).unwrap();
        tokio::time::sleep(Duration::from_millis(10)).await;
        assert_eq!(count.load(Ordering::SeqCst), 2);
        manager.unsubscribe("server", "r").unwrap();
        task.abort();
    }
}
#[tokio::test]
async fn writes_require_contract_status_and_errors_never_include_server_response_or_key() {
    for (status, expected) in [
        (200, AppError::InvalidResponse),
        (400, AppError::RequestRejected),
        (401, AppError::AuthenticationFailed),
        (404, AppError::NotFound),
        (409, AppError::Conflict),
        (500, AppError::UnexpectedServerError),
    ] {
        let app=Router::new().fallback(move ||async move {(StatusCode::from_u16(status).unwrap(),Json(json!({"id":1,"url":"https://example.com","title":"secret-key","displayName":null,"enabled":true,"error":"private details secret-key"})))});
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        let api = HttpReiClient::new(&url, Some(Secret::new("secret-key".into()))).unwrap();
        let error = api
            .workspace(WorkspaceOperation::CreateFeed {
                url: "https://example.com".into(),
                display_name: None,
            })
            .await
            .unwrap_err();
        assert_eq!(error, expected);
        assert!(!format!("{error:?}").contains("secret-key"));
        task.abort();
    }
}
#[tokio::test]
async fn all_read_endpoints_share_safe_error_mapping() {
    let operations = vec![
        WorkspaceOperation::Feeds,
        WorkspaceOperation::Feed { id: 1 },
        WorkspaceOperation::Skills,
        WorkspaceOperation::Skill {
            name: "skill".into(),
        },
        WorkspaceOperation::Profile,
        WorkspaceOperation::Briefing,
        WorkspaceOperation::Search {
            query: "rei".into(),
            vector_top_k: 3,
            web_top_k: 5,
            threshold: 0.5,
        },
        WorkspaceOperation::Reminders,
        WorkspaceOperation::Reminder { id: 1 },
        WorkspaceOperation::Interests { hours: 24 },
        WorkspaceOperation::Memories,
        WorkspaceOperation::Memory { id: "m".into() },
    ];
    for (status, expected) in [
        (400, AppError::RequestRejected),
        (401, AppError::AuthenticationFailed),
        (404, AppError::NotFound),
        (409, AppError::Conflict),
        (503, AppError::UnexpectedServerError),
    ] {
        let app = Router::new().fallback(move || async move {
            (
                StatusCode::from_u16(status).unwrap(),
                "sensitive response must never appear",
            )
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        let api = HttpReiClient::new(&url, Some(Secret::new("key".into()))).unwrap();
        for op in &operations {
            assert_eq!(api.workspace(op.clone()).await.unwrap_err(), expected);
        }
        task.abort();
    }
}
#[tokio::test]
async fn all_read_and_write_contracts_decode_and_reminder_supports_zero_minutes() {
    let feed = json!({"id":1,"url":"https://example.com","title":"Feed","displayName":null,"enabled":true});
    let reminder = json!({"id":1,"message":"Reminder","type":"BEFORE_TARGET","remindAt":"2026-10-04T09:00:00+09:00","targetAt":"2026-10-04T09:00:00+09:00","minutesBefore":0,"notified":false});
    let interest = json!({"id":1,"topic":"rei","reason":"reason","searchQuery":"rei","summary":"summary","sourceUrls":[],"createdAt":"2026-10-03T09:00:00+09:00"});
    let memory = json!({"id":"m","content":"remember","type":"FACT","scope":"GLOBAL","status":"ACTIVE","confidence":0.8,"expiresAt":null,"createdAt":"today","updatedAt":"today"});
    let skill =
        json!({"name":"a b","description":"skill","instructions":"instructions","enabled":true});
    let profile = json!({"total":1,"first":null,"last":null,"countsByType":{"tool.started":1},"durationsByType":{}});
    let briefing = json!({"date":"2026-10-03","overview":"today","events":[],"openTasks":[],"overdueTasks":[],"relatedDocuments":[],"feedItems":[],"interestUpdates":[],"cautionPoints":[],"nextActions":[]});
    let cases = vec![
        (
            WorkspaceOperation::Feed { id: 1 },
            "GET",
            "/api/v1/feed/1",
            feed.clone(),
        ),
        (
            WorkspaceOperation::Skills,
            "GET",
            "/api/v1/skills",
            json!([skill.clone()]),
        ),
        (
            WorkspaceOperation::Skill { name: "a b".into() },
            "GET",
            "/api/v1/skills/a%20b",
            skill,
        ),
        (
            WorkspaceOperation::Profile,
            "GET",
            "/api/v1/profile",
            profile,
        ),
        (
            WorkspaceOperation::Briefing,
            "GET",
            "/api/v1/briefing",
            briefing,
        ),
        (
            WorkspaceOperation::Reminders,
            "GET",
            "/api/v1/reminders",
            json!([reminder.clone()]),
        ),
        (
            WorkspaceOperation::Reminder { id: 1 },
            "GET",
            "/api/v1/reminders/1",
            reminder.clone(),
        ),
        (
            WorkspaceOperation::CreateReminder {
                message: "Reminder".into(),
                at: None,
                target: Some("2026-10-04T09:00:00+09:00".into()),
                minutes_before: Some(0),
            },
            "POST",
            "/api/v1/reminders",
            reminder,
        ),
        (
            WorkspaceOperation::DeleteReminder { id: 1 },
            "DELETE",
            "/api/v1/reminders/1",
            json!(null),
        ),
        (
            WorkspaceOperation::Interests { hours: 12 },
            "GET",
            "/api/v1/interests?hours=12",
            json!([interest.clone()]),
        ),
        (
            WorkspaceOperation::CreateInterest {
                topic: "rei".into(),
                reason: "reason".into(),
                search_query: "rei".into(),
                summary: "summary".into(),
                source_urls: vec![],
            },
            "POST",
            "/api/v1/interests",
            interest,
        ),
        (
            WorkspaceOperation::Memories,
            "GET",
            "/api/v1/memories",
            json!([memory.clone()]),
        ),
        (
            WorkspaceOperation::Memory { id: "m".into() },
            "GET",
            "/api/v1/memories/m",
            memory.clone(),
        ),
        (
            WorkspaceOperation::CreateMemory {
                content: "remember".into(),
                memory_type: "FACT".into(),
                scope: "GLOBAL".into(),
                confidence: 0.8,
            },
            "POST",
            "/api/v1/memories",
            memory,
        ),
        (
            WorkspaceOperation::DeleteMemory { id: "m".into() },
            "DELETE",
            "/api/v1/memories/m",
            json!(null),
        ),
    ];
    for (op, method, path, response) in cases {
        let app = Router::new().fallback(move |req: axum::extract::Request| async move {
            assert_eq!(req.method().as_str(), method);
            assert_eq!(req.uri().to_string(), path);
            assert_eq!(req.headers()["authorization"], "Bearer secret");
            let status = if method == "DELETE" {
                StatusCode::NO_CONTENT
            } else if method == "POST" {
                StatusCode::CREATED
            } else {
                StatusCode::OK
            };
            (status, Json(response))
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
        let result = api
            .workspace(op.clone())
            .await
            .unwrap_or_else(|e| panic!("{op:?}: {e:?}"));
        assert_eq!(result.items.is_empty(), method == "DELETE");
        task.abort();
    }
}

struct Observer;
impl rei_client_lib::ports::RunObserver for Observer {
    fn changed(&self, _: rei_client_lib::application::RunView) {}
}
impl rei_client_lib::ports::NotificationPort for Observer {
    fn notify(&self, _: &rei_client_lib::application::RunView) -> Result<()> {
        Ok(())
    }
}
#[tokio::test]
async fn accepted_cancel_waits_for_authoritative_status_before_closing_stream() {
    use rei_client_lib::application::*;
    use std::sync::Arc;
    let snapshot = json!({"runId":"r","sessionId":null,"turnId":null,"projectId":"p","status":"CANCELLED","failure":null});
    let copy = snapshot.clone();
    let app = Router::new()
        .route(
            "/api/v1/runs/r/cancel",
            post(move || {
                let s = copy.clone();
                async move { (StatusCode::ACCEPTED, Json(s)) }
            }),
        )
        .route(
            "/api/v1/runs/r",
            get(move || {
                let s = snapshot.clone();
                async move { Json(s) }
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = Arc::new(HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap());
    let manager = RunManager::new(
        Arc::new(Observer),
        Arc::new(Observer),
        std::time::Duration::from_secs(1),
    );
    manager
        .register(Projection::background("server", "p", "r", "summary"))
        .unwrap();
    manager.cancel("server", "r", api.clone()).await.unwrap();
    let view = manager.get("server", "r").unwrap();
    assert_eq!(view.status, RunStatus::Queued);
    assert!(view.cancel_requested);
    let view = manager.refresh("server", "r", api).await.unwrap();
    assert_eq!(view.status, RunStatus::Cancelled);
    assert!(!view.cancel_requested);
}

#[tokio::test]
async fn non_chat_runs_accept_only_run_metadata_and_keep_null_ownership() {
    let app=Router::new()
        .route("/api/v1/summaries",post(|Json(b):Json<serde_json::Value>|async move {
            assert_eq!(b,json!({"projectId":"p","url":"https://example.com"}));
            (StatusCode::ACCEPTED,[("location","/api/v1/runs/r")],Json(json!({"runId":"r"})))
        }))
        .route("/api/v1/images",post(|Json(b):Json<serde_json::Value>|async move {
            assert_eq!(b,json!({"projectId":"p","prompt":"cat","size":"1024x1024"}));
            (StatusCode::ACCEPTED,[("location","/api/v1/runs/r")],Json(json!({"runId":"r"})))
        }))
        .route("/api/v1/runs/r",get(||async {Json(json!({"runId":"r","sessionId":null,"turnId":null,"projectId":"p","status":"RUNNING","failure":null}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert_eq!(
        api.background(
            "p",
            BackgroundOperation::Summary {
                url: "https://example.com".into()
            }
        )
        .await
        .unwrap()
        .run_id,
        "r"
    );
    assert_eq!(
        api.background(
            "p",
            BackgroundOperation::Image {
                prompt: "cat".into(),
                size: Some("1024x1024".into())
            }
        )
        .await
        .unwrap()
        .run_id,
        "r"
    );
    let snapshot = api.run("r").await.unwrap();
    assert!(snapshot.session_id.is_empty());
    assert!(snapshot.turn_id.is_empty());
    let mut projection =
        rei_client_lib::application::Projection::background("server", "p", "r", "Summary");
    projection.recover(snapshot).unwrap();
    assert_eq!(projection.status, RunStatus::Running);
}

#[tokio::test]
async fn stateful_operations_have_explicit_methods_and_no_arbitrary_fields() {
    let app=Router::new()
        .route("/api/v1/feed",post(|h:HeaderMap,Json(b):Json<serde_json::Value>|async move {
            assert_eq!(h["authorization"],"Bearer secret");
            assert_eq!(b,json!({"url":"https://example.com/rss","displayName":"News"}));
            (StatusCode::CREATED,Json(json!({"id":1,"url":b["url"],"displayName":b["displayName"],"title":null,"enabled":true})))
        }))
        .route("/api/v1/feed/1",axum::routing::patch(|Json(b):Json<serde_json::Value>|async move {
            assert_eq!(b,json!({"enabled":false}));
            Json(json!({"id":1,"url":"https://example.com/rss","displayName":null,"title":"News","enabled":false}))
        }).delete(||async {StatusCode::NO_CONTENT}))
        .route("/api/v1/memories/unknown",get(||async {StatusCode::NOT_FOUND}))
        .route("/api/v1/skills/reload",post(||async {Json(json!({"count":4}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    assert_eq!(
        api.workspace(WorkspaceOperation::CreateFeed {
            url: "https://example.com/rss".into(),
            display_name: Some("News".into())
        })
        .await
        .unwrap()
        .items[0]
            .id
            .as_deref(),
        Some("1")
    );
    api.workspace(WorkspaceOperation::UpdateFeed {
        id: 1,
        display_name: None,
        enabled: Some(false),
    })
    .await
    .unwrap();
    assert!(api
        .workspace(WorkspaceOperation::DeleteFeed { id: 1 })
        .await
        .unwrap()
        .items
        .is_empty());
    assert_eq!(
        api.workspace(WorkspaceOperation::Memory {
            id: "unknown".into()
        })
        .await
        .unwrap_err(),
        AppError::NotFound
    );
    assert_eq!(
        api.workspace(WorkspaceOperation::ReloadSkills)
            .await
            .unwrap()
            .items[0]
            .fields[0]
            .1,
        "4"
    );
    assert!(serde_json::from_value::<WorkspaceOperation>(
        json!({"operation":"createFeed","url":"https://example.com","path":"/tmp"})
    )
    .is_err());
    assert_eq!(
        api.workspace(WorkspaceOperation::CreateMemory {
            content: "x".into(),
            memory_type: "FACT".into(),
            scope: "SESSION".into(),
            confidence: 0.8
        })
        .await
        .unwrap_err(),
        AppError::InvalidInput
    );
}

#[tokio::test]
async fn read_operations_use_existing_authenticated_transport_and_map_resource_errors() {
    let app = Router::new()
        .route("/api/v1/feed", get(|h: HeaderMap| async move {
            assert_eq!(h["authorization"], "Bearer secret");
            Json(json!([{ "id":1,"url":"https://example.com/rss","title":"News","displayName":null,"enabled":true}]))
        }))
        .route("/api/v1/feed/9", get(|| async { StatusCode::NOT_FOUND }))
        .route("/api/v1/search", post(|Json(body): Json<serde_json::Value>| async move {
            assert_eq!(body, json!({"query":"rei","vectorTopK":3,"webTopK":5,"threshold":0.5}));
            Json(json!({"query":"rei","vectorResults":[],"webResults":[]}))
        }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let api = HttpReiClient::new(&url, Some(Secret::new("secret".into()))).unwrap();
    let feeds = api.workspace(WorkspaceOperation::Feeds).await.unwrap();
    assert_eq!(feeds.items[0].id.as_deref(), Some("1"));
    assert_eq!(feeds.items[0].title, "News");
    assert_eq!(
        api.workspace(WorkspaceOperation::Feed { id: 9 })
            .await
            .unwrap_err(),
        AppError::NotFound
    );
    let result = api
        .workspace(WorkspaceOperation::Search {
            query: "rei".into(),
            vector_top_k: 3,
            web_top_k: 5,
            threshold: 0.5,
        })
        .await
        .unwrap();
    assert!(result.items.is_empty());
}
