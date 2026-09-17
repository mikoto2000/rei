use async_trait::async_trait;
use axum::{
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use rei_client_lib::{api::HttpReiClient, application::*, domain::*, ports::*};
use serde_json::json;
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc, Mutex,
};
use std::time::Duration;

struct UnavailableStream {
    error: AppError,
    polls: AtomicUsize,
    final_status: RunStatus,
}

fn live_frame(kind: &str, sequence: u64, payload: serde_json::Value) -> String {
    let data = json!({"type":kind,"sequence":sequence,"version":1,"runId":"r","sessionId":"s",
        "turnId":"t","projectId":"p","timestamp":"2026-09-17T01:00:00Z","payload":payload});
    format!("event: {kind}\nid: {sequence}\ndata: {data}\n\n")
}

#[tokio::test]
async fn submitted_chat_projects_live_activity_across_disconnect_and_replay() {
    let connections = Arc::new(AtomicUsize::new(0));
    let calls = connections.clone();
    let app = Router::new()
        .route(
            "/api/v1/chat",
            post(|Json(body): Json<serde_json::Value>| async move {
                assert_eq!(body["projectId"], "p");
                assert_eq!(body["message"], "hello");
                (
                    StatusCode::ACCEPTED,
                    Json(json!({"runId":"r","sessionId":"s","turnId":"t"})),
                )
            }),
        )
        .route(
            "/api/v1/runs/r/events",
            get(move |headers: HeaderMap| {
                let calls = calls.clone();
                async move {
                    let attempt = calls.fetch_add(1, Ordering::SeqCst);
                    assert_eq!(headers["authorization"], "Bearer test-key");
                    let frames = if attempt == 0 {
                        assert!(!headers.contains_key("last-event-id"));
                        vec![
                            live_frame("agent.run.started", 1, json!({})),
                            live_frame(
                                "llm.request.started",
                                3,
                                json!({"requestId":"q","feature":"chat"}),
                            ),
                            live_frame(
                                "tool.started",
                                5,
                                json!({"toolCallId":"call","toolName":"readFile"}),
                            ),
                        ]
                    } else {
                        assert_eq!(headers["last-event-id"], "5");
                        vec![
                            live_frame(
                                "tool.started",
                                5,
                                json!({"toolCallId":"call","toolName":"readFile"}),
                            ),
                            live_frame(
                                "tool.completed",
                                7,
                                json!({"toolCallId":"call","toolName":"readFile","duration":18}),
                            ),
                            live_frame(
                                "message.started",
                                8,
                                json!({"messageId":"m","role":"assistant"}),
                            ),
                            live_frame("message.delta", 9, json!({"messageId":"m","delta":"hel"})),
                            live_frame("message.delta", 9, json!({"messageId":"m","delta":"hel"})),
                            "event: heartbeat\ndata: {}\n\n".into(),
                            live_frame(
                                "llm.response.first_token",
                                10,
                                json!({"requestId":"q","durationMs":70}),
                            ),
                            live_frame("message.delta", 11, json!({"messageId":"m","delta":"lo"})),
                            live_frame(
                                "llm.response.completed",
                                12,
                                json!({"requestId":"q","durationMs":110}),
                            ),
                            live_frame(
                                "message.completed",
                                13,
                                json!({"messageId":"m","role":"assistant","text":"hello"}),
                            ),
                            live_frame("agent.run.completed", 15, json!({"duration":120})),
                        ]
                    };
                    // A streaming HTTP body exercises parser chunk boundaries as well as reconnect.
                    let chunks = futures_util::stream::iter(
                        frames.into_iter().map(|s| Ok::<_, std::io::Error>(s)),
                    );
                    Response::builder()
                        .header("content-type", "text/event-stream")
                        .body(axum::body::Body::from_stream(chunks))
                        .unwrap()
                }
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let api = Arc::new(
        HttpReiClient::new(
            &format!("http://{}", listener.local_addr().unwrap()),
            Some(Secret::new("test-key".into())),
        )
        .unwrap(),
    );
    let server = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let receipt = api.chat("p", None, "hello").await.unwrap();
    let observer = Arc::new(Observer::default());
    let manager = Arc::new(RunManager::new(
        observer.clone(),
        observer,
        Duration::from_millis(1),
    ));
    manager
        .register(Projection::new(
            "server",
            "conversation",
            "p",
            receipt,
            "hello",
        ))
        .unwrap();
    manager.subscribe("server", "r", api).unwrap();
    let view = completed(&manager).await;
    assert_eq!(view.assistant_text, "hello");
    assert!(view.messages[0].completed);
    assert_eq!(view.tools.len(), 1);
    assert_eq!(view.tools[0].status, "COMPLETED");
    assert_eq!(view.tools[0].duration_ms, Some(18));
    assert!(view.tools[0].started_at.is_some());
    assert_eq!(view.activities.len(), 1);
    assert_eq!(view.activities[0].first_token_ms, Some(70));
    assert_eq!(view.activities[0].duration_ms, Some(110));
    assert_eq!(view.status, RunStatus::Completed);
    assert_eq!(view.last_sequence.as_deref(), Some("15"));
    assert!(!view.incomplete);
    assert_eq!(connections.load(Ordering::SeqCst), 2);
    server.abort();
}
#[async_trait]
impl ReiClient for UnavailableStream {
    async fn health(&self) -> Result<()> {
        Ok(())
    }
    async fn projects(&self) -> Result<Vec<Project>> {
        Ok(vec![])
    }
    async fn chat(&self, _: &str, _: Option<&str>, _: &str) -> Result<ChatReceipt> {
        Err(AppError::InvalidInput)
    }
    async fn run(&self, _: &str) -> Result<RunSnapshot> {
        let n = self.polls.fetch_add(1, Ordering::SeqCst);
        Ok(RunSnapshot {
            run_id: "r".into(),
            session_id: "s".into(),
            project_id: "p".into(),
            turn_id: "t".into(),
            status: if n == 0 {
                RunStatus::Running
            } else {
                self.final_status
            },
            failure: None,
        })
    }
    async fn cancel(&self, _: &str) -> Result<RunSnapshot> {
        Err(AppError::RunNotFound)
    }
    async fn events(&self, _: &str, _: Option<u64>) -> Result<ByteStream> {
        Err(self.error)
    }
}

#[tokio::test]
async fn replay_gap_polls_nonterminal_until_failed_and_notifies_once() {
    let (manager, observer, _, _) = fixture(false).await;
    let api = Arc::new(UnavailableStream {
        error: AppError::ReplayGap,
        polls: AtomicUsize::new(0),
        final_status: RunStatus::Failed,
    });
    manager.subscribe("server", "r", api.clone()).unwrap();
    let p = completed(&manager).await;
    assert!(p.incomplete);
    assert_eq!(p.status, RunStatus::Failed);
    assert!(api.polls.load(Ordering::SeqCst) >= 2);
    assert_eq!(*observer.notices.lock().unwrap(), vec![RunStatus::Failed]);
}
#[tokio::test]
async fn detects_terminal_during_reconnect_outage() {
    let (manager, _, _, _) = fixture(false).await;
    manager
        .subscribe(
            "server",
            "r",
            Arc::new(UnavailableStream {
                error: AppError::ServerUnreachable,
                polls: AtomicUsize::new(0),
                final_status: RunStatus::Completed,
            }),
        )
        .unwrap();
    let p = completed(&manager).await;
    assert!(p.incomplete);
    assert_eq!(p.stream_state, StreamState::Closed);
    assert_eq!(p.error, None);
}
#[tokio::test]
async fn authentication_failure_closes_stream_without_reconnect_loop() {
    let (manager, _, _, _) = fixture(false).await;
    manager
        .subscribe(
            "server",
            "r",
            Arc::new(UnavailableStream {
                error: AppError::AuthenticationFailed,
                polls: AtomicUsize::new(0),
                final_status: RunStatus::Completed,
            }),
        )
        .unwrap();
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let p = manager.get("server", "r").unwrap();
            if p.stream_state == StreamState::Closed {
                assert_eq!(p.error, Some(AppError::AuthenticationFailed));
                break;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(manager.active().len(), 1);
}

#[derive(Default)]
struct Observer {
    notices: Mutex<Vec<RunStatus>>,
}
impl RunObserver for Observer {
    fn changed(&self, _: RunView) {}
}
impl NotificationPort for Observer {
    fn notify(&self, run: &RunView) -> Result<()> {
        self.notices.lock().unwrap().push(run.status);
        Err(AppError::Cancelled)
    }
}
async fn fixture(
    gap: bool,
) -> (
    Arc<RunManager>,
    Arc<Observer>,
    Arc<AtomicUsize>,
    Arc<dyn ReiClient>,
) {
    let connections = Arc::new(AtomicUsize::new(0));
    let count = connections.clone();
    let app=Router::new().route("/api/v1/runs/r/events",get(move |headers:HeaderMap| {
        let count=count.clone();async move {
            assert_eq!(headers["authorization"],"Bearer test-key");
            let attempt=count.fetch_add(1,Ordering::SeqCst);
            if attempt>0 {assert_eq!(headers["last-event-id"],"101");}
            if gap && attempt>0 {return StatusCode::CONFLICT.into_response();}
            let delta="event: message.delta\nid: 101\ndata: {\"type\":\"message.delta\",\"sequence\":101,\"version\":1,\"runId\":\"r\",\"payload\":{\"messageId\":\"m\",\"delta\":\"hello\"}}\n\n";
            let terminal="event: agent.run.completed\nid: 109\ndata: {\"type\":\"agent.run.completed\",\"sequence\":109,\"version\":1,\"runId\":\"r\",\"payload\":{}}\n\n";
            Response::builder().header("content-type","text/event-stream").body(axum::body::Body::from(if attempt==0 {delta.into()} else {format!("{delta}{terminal}")})).unwrap()
        }
    })).route("/api/v1/runs/r",get(||async {Json(json!({"runId":"r","projectId":"p","sessionId":"s","turnId":"t","status":"COMPLETED","failure":null}))}))
    .route("/api/v1/runs/r/cancel",post(||async {Json(json!({"runId":"r","projectId":"p","sessionId":"s","turnId":"t","status":"CANCELLED","failure":null}))}));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let api: Arc<dyn ReiClient> = Arc::new(
        HttpReiClient::new(
            &format!("http://{}", listener.local_addr().unwrap()),
            Some(Secret::new("test-key".into())),
        )
        .unwrap(),
    );
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let observer = Arc::new(Observer::default());
    let manager = Arc::new(RunManager::new(
        observer.clone(),
        observer.clone(),
        Duration::from_millis(1),
    ));
    manager
        .register(Projection::new(
            "server",
            "conversation",
            "p",
            ChatReceipt {
                run_id: "r".into(),
                session_id: "s".into(),
                turn_id: "t".into(),
            },
            "hello",
        ))
        .unwrap();
    (manager, observer, connections, api)
}
async fn completed(manager: &RunManager) -> RunView {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let p = manager.get("server", "r").unwrap();
            if p.status.terminal() {
                return p;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap()
}
#[tokio::test]
async fn disconnect_reconnect_replays_once_and_permission_denial_is_harmless() {
    let (manager, observer, count, api) = fixture(false).await;
    manager.subscribe("server", "r", api.clone()).unwrap();
    manager.subscribe("server", "r", api).unwrap();
    let p = completed(&manager).await;
    assert_eq!(p.assistant_text, "hello");
    assert_eq!(p.status, RunStatus::Completed);
    assert!(!p.incomplete);
    assert_eq!(count.load(Ordering::SeqCst), 2);
    assert!(manager.active().is_empty());
    assert_eq!(
        *observer.notices.lock().unwrap(),
        vec![RunStatus::Completed]
    );
}
#[tokio::test]
async fn replay_gap_recovers_status_and_preserves_incomplete_flag() {
    let (manager, _, _, api) = fixture(true).await;
    manager.subscribe("server", "r", api).unwrap();
    let p = completed(&manager).await;
    assert!(p.incomplete);
    assert_eq!(p.status, RunStatus::Completed);
    assert_eq!(p.assistant_text, "hello");
}
#[tokio::test]
async fn cancel_is_explicit_and_unknown_run_fails() {
    let (manager, _, _, api) = fixture(false).await;
    assert_eq!(
        manager
            .cancel("server", "unknown", api.clone())
            .await
            .unwrap_err(),
        AppError::RunNotFound
    );
    manager.cancel("server", "r", api.clone()).await.unwrap();
    manager.cancel("server", "r", api).await.unwrap();
    assert_eq!(
        manager.get("server", "r").unwrap().status,
        RunStatus::Cancelled
    );
}
#[tokio::test]
async fn multiple_servers_can_have_the_same_run_id() {
    let (manager, _, _, _) = fixture(false).await;
    manager
        .register(Projection::new(
            "other-server",
            "other-conversation",
            "other-project",
            ChatReceipt {
                run_id: "r".into(),
                session_id: "s2".into(),
                turn_id: "t2".into(),
            },
            "hi",
        ))
        .unwrap();
    assert_eq!(manager.active().len(), 2);
    assert_eq!(
        manager.get("other-server", "r").unwrap().conversation_id,
        "other-conversation"
    );
    manager.unsubscribe("server", "r").unwrap();
    assert_eq!(
        manager.get("server", "r").unwrap().stream_state,
        StreamState::Closed
    );
}

#[tokio::test]
async fn run_list_preserves_submission_order() {
    let (manager, _, _, _) = fixture(false).await;
    for i in 0..20 {
        manager
            .register(Projection::new(
                "server",
                "conversation",
                "p",
                ChatReceipt {
                    run_id: format!("run-{i}"),
                    session_id: "s".into(),
                    turn_id: "t".into(),
                },
                &i.to_string(),
            ))
            .unwrap();
    }
    let ids = manager
        .all()
        .into_iter()
        .map(|r| r.run_id)
        .collect::<Vec<_>>();
    assert_eq!(
        ids,
        std::iter::once("r".to_owned())
            .chain((0..20).map(|i| format!("run-{i}")))
            .collect::<Vec<_>>()
    );
}

#[tokio::test]
async fn status_refresh_reports_missing_terminal_event_as_incomplete() {
    let (manager, _, _, api) = fixture(false).await;
    let view = manager.refresh("server", "r", api).await.unwrap();
    assert_eq!(view.status, RunStatus::Completed);
    assert!(view.incomplete);
}

#[tokio::test]
async fn running_run_can_be_cancelled_by_its_explicit_id() {
    let (manager, _, _, api) = fixture(false).await;
    manager
        .refresh(
            "server",
            "r",
            Arc::new(UnavailableStream {
                error: AppError::StreamDisconnected,
                polls: AtomicUsize::new(0),
                final_status: RunStatus::Completed,
            }),
        )
        .await
        .unwrap();
    assert_eq!(
        manager.get("server", "r").unwrap().status,
        RunStatus::Running
    );
    manager.cancel("server", "r", api).await.unwrap();
    assert_eq!(
        manager.get("server", "r").unwrap().status,
        RunStatus::Cancelled
    );
}
