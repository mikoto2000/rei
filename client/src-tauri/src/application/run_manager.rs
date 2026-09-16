use super::{backoff, Projection, SseParser, ToolExecution, WorkingSetItem};
use crate::{domain::*, ports::*};
use futures_util::StreamExt;
use serde::Serialize;
use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::task::JoinHandle;

/// Explicit UI boundary DTO, independent of event envelopes and internal state.
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunView {
    pub revision: u64,
    pub server_id: String,
    pub conversation_id: String,
    pub project_id: String,
    pub run_id: String,
    pub session_id: String,
    pub turn_id: String,
    pub prompt: String,
    pub status: RunStatus,
    pub stream_state: StreamState,
    pub assistant_text: String,
    pub last_sequence: Option<String>,
    pub incomplete: bool,
    pub error: Option<AppError>,
    pub failure: Option<String>,
    pub tools: Vec<ToolExecution>,
    pub working_set: Vec<WorkingSetItem>,
}
impl From<&Projection> for RunView {
    fn from(p: &Projection) -> Self {
        Self {
            revision: p.revision,
            server_id: p.server_id.clone(),
            conversation_id: p.conversation_id.clone(),
            project_id: p.project_id.clone(),
            run_id: p.run_id.clone(),
            session_id: p.session_id.clone(),
            turn_id: p.turn_id.clone(),
            prompt: p.prompt.clone(),
            status: p.status,
            stream_state: p.stream_state,
            assistant_text: p.assistant_text(),
            last_sequence: p.last_sequence.map(|v| v.to_string()),
            incomplete: p.incomplete,
            error: p.error,
            failure: p.failure.clone(),
            tools: p.tools.values().cloned().collect(),
            working_set: p.working_set.values().cloned().collect(),
        }
    }
}
type Key = (String, String);
pub struct RunManager {
    runs: Mutex<HashMap<Key, Projection>>,
    tasks: Mutex<HashMap<Key, JoinHandle<()>>>,
    notified: Mutex<HashSet<Key>>,
    observer: Arc<dyn RunObserver>,
    notifications: Arc<dyn NotificationPort>,
    retry_unit: Duration,
}
impl RunManager {
    pub fn new(
        observer: Arc<dyn RunObserver>,
        notifications: Arc<dyn NotificationPort>,
        retry_unit: Duration,
    ) -> Self {
        Self {
            runs: Mutex::new(HashMap::new()),
            tasks: Mutex::new(HashMap::new()),
            notified: Mutex::new(HashSet::new()),
            observer,
            notifications,
            retry_unit,
        }
    }
    pub fn register(&self, mut p: Projection) -> Result<()> {
        let key = (p.server_id.clone(), p.run_id.clone());
        let mut runs = self.runs.lock().unwrap();
        if runs.contains_key(&key) {
            return Err(AppError::Busy);
        }
        p.registered_order = runs.len();
        let view = RunView::from(&p);
        runs.insert(key, p);
        drop(runs);
        self.observer.changed(view);
        Ok(())
    }
    pub fn get(&self, server: &str, run: &str) -> Result<RunView> {
        self.runs
            .lock()
            .unwrap()
            .get(&(server.into(), run.into()))
            .map(RunView::from)
            .ok_or(AppError::RunNotFound)
    }
    pub fn all(&self) -> Vec<RunView> {
        let runs = self.runs.lock().unwrap();
        let mut ordered = runs.values().collect::<Vec<_>>();
        ordered.sort_by_key(|p| p.registered_order);
        ordered.into_iter().map(RunView::from).collect()
    }
    pub fn active(&self) -> Vec<RunView> {
        self.all()
            .into_iter()
            .filter(|p| !p.status.terminal())
            .collect()
    }
    fn update(&self, key: &Key, f: impl FnOnce(&mut Projection) -> Result<()>) -> Result<()> {
        let mut runs = self.runs.lock().unwrap();
        let p = runs.get_mut(key).ok_or(AppError::RunNotFound)?;
        f(p)?;
        p.revision += 1;
        let view = RunView::from(&*p);
        drop(runs);
        if view.status.terminal() && self.notified.lock().unwrap().insert(key.clone()) {
            let _ = self.notifications.notify(&view);
        }
        self.observer.changed(view);
        Ok(())
    }
    pub async fn refresh(
        &self,
        server: &str,
        run: &str,
        api: Arc<dyn ReiClient>,
    ) -> Result<RunView> {
        self.get(server, run)?;
        let snapshot = api.run(run).await?;
        self.update(&(server.into(), run.into()), |p| {
            if snapshot.status.terminal() && !p.status.terminal() {
                p.incomplete = true;
            }
            p.recover(snapshot)
        })?;
        self.get(server, run)
    }
    pub async fn cancel(&self, server: &str, run: &str, api: Arc<dyn ReiClient>) -> Result<()> {
        self.get(server, run)?;
        let snapshot = api.cancel(run).await?;
        self.update(&(server.into(), run.into()), |p| p.recover(snapshot))?;
        if self.get(server, run)?.status.terminal() {
            self.unsubscribe(server, run)?;
        }
        Ok(())
    }
    pub fn unsubscribe(&self, server: &str, run: &str) -> Result<()> {
        let key = (server.into(), run.into());
        if let Some(task) = self.tasks.lock().unwrap().remove(&key) {
            task.abort();
        }
        self.update(&key, |p| {
            p.stream_state = StreamState::Closed;
            Ok(())
        })
    }
    pub fn subscribe(
        self: &Arc<Self>,
        server: &str,
        run: &str,
        api: Arc<dyn ReiClient>,
    ) -> Result<()> {
        if self.get(server, run)?.status.terminal() {
            return Ok(());
        }
        let key = (server.into(), run.into());
        let mut tasks = self.tasks.lock().unwrap();
        if tasks.get(&key).is_some_and(|task| !task.is_finished()) {
            return Ok(());
        }
        let manager = self.clone();
        let task_key = key.clone();
        tasks.insert(
            key,
            tokio::spawn(async move {
                manager.drive(task_key, api).await;
            }),
        );
        Ok(())
    }
    async fn drive(self: Arc<Self>, key: Key, api: Arc<dyn ReiClient>) {
        let mut attempt = 0usize;
        let mut gap = false;
        loop {
            if self
                .get(&key.0, &key.1)
                .map_or(true, |p| p.status.terminal())
            {
                break;
            }
            if gap {
                match api.run(&key.1).await {
                    Ok(snapshot) => {
                        let _ = self.update(&key, |p| p.recover(snapshot));
                    }
                    Err(error) => {
                        let _ = self.update(&key, |p| {
                            p.error = Some(error);
                            Ok(())
                        });
                    }
                }
            } else {
                let last = self
                    .runs
                    .lock()
                    .unwrap()
                    .get(&key)
                    .and_then(|p| p.last_sequence);
                let _ = self.update(&key, |p| {
                    p.stream_state = if attempt == 0 {
                        StreamState::Connecting
                    } else {
                        StreamState::Reconnecting
                    };
                    Ok(())
                });
                match api.events(&key.1, last).await {
                    Ok(mut stream) => {
                        let _ = self.update(&key, |p| {
                            p.stream_state = StreamState::Connected;
                            p.error = None;
                            Ok(())
                        });
                        let mut parser = SseParser::default();
                        loop {
                            if self
                                .get(&key.0, &key.1)
                                .map_or(true, |p| p.status.terminal())
                            {
                                return;
                            }
                            match tokio::time::timeout(Duration::from_secs(45), stream.next()).await
                            {
                                Ok(Some(Ok(bytes))) => match parser.push(&bytes) {
                                    Ok(frames) => {
                                        for frame in frames {
                                            if self
                                                .update(&key, |p| {
                                                    p.apply(frame)?;
                                                    Ok(())
                                                })
                                                .is_err()
                                            {
                                                gap = true;
                                                break;
                                            }
                                        }
                                        if gap {
                                            break;
                                        }
                                        if self
                                            .runs
                                            .lock()
                                            .unwrap()
                                            .get(&key)
                                            .and_then(|p| p.last_sequence)
                                            != last
                                        {
                                            attempt = 0;
                                        }
                                    }
                                    Err(_) => {
                                        gap = true;
                                        break;
                                    }
                                },
                                _ => break,
                            }
                        }
                    }
                    Err(AppError::ReplayGap) => gap = true,
                    Err(
                        error @ (AppError::AuthenticationFailed
                        | AppError::RunNotFound
                        | AppError::InvalidResponse),
                    ) => {
                        let _ = self.update(&key, |p| {
                            p.error = Some(error);
                            p.stream_state = StreamState::Closed;
                            Ok(())
                        });
                        return;
                    }
                    Err(_) => {}
                }
            }
            if self
                .get(&key.0, &key.1)
                .map_or(true, |p| p.status.terminal())
            {
                break;
            }
            let _ = self.update(&key, |p| {
                p.stream_state = StreamState::Reconnecting;
                p.error = Some(if gap {
                    AppError::ReplayGap
                } else {
                    AppError::StreamDisconnected
                });
                p.incomplete |= gap;
                Ok(())
            });
            // If replay is unavailable, poll status without pretending to reconstruct lost text.
            // Also detect completion during a sustained connection outage.
            if !gap && attempt >= 2 {
                if let Ok(snapshot) = api.run(&key.1).await {
                    if snapshot.status.terminal() {
                        let _ = self.update(&key, |p| {
                            p.incomplete = true;
                            p.recover(snapshot)
                        });
                        break;
                    }
                }
            }
            let delay = self.retry_unit.mul_f64(backoff(attempt) as f64);
            tokio::time::sleep(delay).await;
            attempt = attempt.saturating_add(1);
        }
    }
}
impl Drop for RunManager {
    fn drop(&mut self) {
        for task in self.tasks.get_mut().unwrap().values() {
            task.abort();
        }
    }
}
