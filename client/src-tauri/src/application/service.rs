use super::*;
use crate::{domain::*, ports::*};
use serde::Serialize;
use std::{
    collections::HashSet,
    sync::{Arc, Mutex},
    time::Duration,
};

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerView {
    pub id: String,
    pub name: String,
    pub base_url: String,
    pub has_credential: bool,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionView {
    pub server_id: String,
    pub state: ConnectionState,
    pub reachable: bool,
    pub authenticated: bool,
    pub error: Option<AppError>,
}
impl ConnectionView {
    fn failed(server: &str, error: AppError, reachable: bool) -> Self {
        Self {
            server_id: server.into(),
            state: match error {
                AppError::AuthenticationFailed | AppError::VaultLocked => {
                    ConnectionState::AuthFailed
                }
                AppError::ServerUnreachable | AppError::RequestTimeout => {
                    ConnectionState::ServerUnreachable
                }
                _ => ConnectionState::ConnectionFailed,
            },
            reachable,
            authenticated: false,
            error: Some(error),
        }
    }
}

pub struct Application {
    pub conversations: ConversationService,
    pub runs: Arc<RunManager>,
    vault: Mutex<Option<Box<dyn CredentialStore>>>,
    credentials: Arc<dyn CredentialFactory>,
    clients: Arc<dyn ClientFactory>,
    submitting: Mutex<HashSet<String>>,
}
impl Application {
    pub async fn workspace(
        &self,
        server: &str,
        operation: WorkspaceOperation,
    ) -> Result<WorkspaceResult> {
        self.api(server, true)?.workspace(operation).await
    }
    pub async fn checkpoint(
        &self,
        server: &str,
        project: &str,
        task: &str,
        resume: bool,
    ) -> Result<RunView> {
        let key = format!("checkpoint:{server}:{project}:{task}");
        {
            let mut active = self.submitting.lock().unwrap();
            if !active.insert(key.clone()) {
                return Err(AppError::Busy);
            }
        }
        let _reservation = Submission {
            ids: &self.submitting,
            id: key,
        };
        let api = self.api(server, true)?;
        if !api.projects().await?.iter().any(|p| p.id == project) {
            return Err(AppError::ProjectNotFound);
        }
        let receipt = if resume {
            api.resume_checkpoint(project, task).await?
        } else {
            api.checkpoint_run(project, task).await?
        };
        if receipt.task_id != task
            || receipt.snapshot.project_id != project
            || receipt.snapshot.session_id.is_empty()
        {
            return Err(AppError::InvalidResponse);
        }
        self.track_saved_snapshot(
            server,
            project,
            &format!("Checkpoint {task}"),
            receipt.snapshot,
            api,
        )
        .await
    }
    pub async fn goal_track(&self, server: &str, project: &str, goal: &str) -> Result<RunView> {
        let key = format!("goal:{server}:{project}:{goal}");
        if !self.submitting.lock().unwrap().insert(key.clone()) {
            return Err(AppError::Busy);
        }
        let _reservation = Submission {
            ids: &self.submitting,
            id: key,
        };
        let api = self.api(server, true)?;
        if !api.projects().await?.iter().any(|p| p.id == project) {
            return Err(AppError::ProjectNotFound);
        }
        let snapshot = api.goal_snapshot(project, goal).await?;
        if snapshot.project_id != project
            || snapshot.session_id.is_empty()
            || snapshot.run_id.is_empty()
        {
            return Err(AppError::InvalidResponse);
        }
        self.track_saved_snapshot(server, project, &format!("Goal {goal}"), snapshot, api)
            .await
    }
    pub async fn schedule_track(
        &self,
        server: &str,
        project: &str,
        schedule: &str,
    ) -> Result<RunView> {
        let key = format!("schedule:{server}:{project}:{schedule}");
        if !self.submitting.lock().unwrap().insert(key.clone()) {
            return Err(AppError::Busy);
        }
        let _reservation = Submission {
            ids: &self.submitting,
            id: key,
        };
        let api = self.api(server, true)?;
        if !api.projects().await?.iter().any(|p| p.id == project) {
            return Err(AppError::ProjectNotFound);
        }
        let snapshot = api.schedule_snapshot(project, schedule).await?;
        if snapshot.project_id != project
            || snapshot.session_id.is_empty()
            || snapshot.run_id.is_empty()
        {
            return Err(AppError::InvalidResponse);
        }
        self.track_saved_snapshot(
            server,
            project,
            &format!("Schedule {schedule}"),
            snapshot,
            api,
        )
        .await
    }
    async fn track_saved_snapshot(
        &self,
        server: &str,
        project: &str,
        prompt: &str,
        snapshot: RunSnapshot,
        api: Arc<dyn ReiClient>,
    ) -> Result<RunView> {
        let run = snapshot.run_id.clone();
        if let Ok(existing) = self.runs.get(server, &run) {
            if existing.project_id != snapshot.project_id
                || existing.session_id != snapshot.session_id
                || existing.turn_id != snapshot.turn_id
            {
                return Err(AppError::InvalidResponse);
            }
            let updated = self.runs.refresh(server, &run, api.clone()).await?;
            self.runs.subscribe(server, &run, api)?;
            return Ok(updated);
        }
        let mut projection = Projection::new(
            server,
            "",
            project,
            ChatReceipt {
                run_id: run.clone(),
                session_id: snapshot.session_id.clone(),
                turn_id: snapshot.turn_id.clone(),
            },
            prompt,
        );
        projection.recover(snapshot)?;
        self.runs.register(projection)?;
        self.runs.subscribe(server, &run, api)?;
        self.runs.get(server, &run)
    }
    pub async fn background(
        &self,
        server: &str,
        project: &str,
        operation: BackgroundOperation,
    ) -> Result<RunView> {
        let prompt = match &operation {
            BackgroundOperation::Summary { url } => format!("Summarize: {url}"),
            BackgroundOperation::Image { prompt, .. } => format!("Image: {prompt}"),
        };
        let api = self.api(server, true)?;
        // Registered project IDs only, consistent with new-conversation selection.
        if !api.projects().await?.iter().any(|p| p.id == project) {
            return Err(AppError::ProjectNotFound);
        }
        let receipt = api.background(project, operation).await?;
        self.runs.register(Projection::background(
            server,
            project,
            &receipt.run_id,
            &prompt,
        ))?;
        self.runs.subscribe(server, &receipt.run_id, api)?;
        self.runs.get(server, &receipt.run_id)
    }
    pub async fn session_list(
        &self,
        server: &str,
        project: Option<&str>,
        limit: Option<i32>,
        cursor: Option<String>,
    ) -> Result<Page<SessionSummary>> {
        SessionHistoryService::new(self.api(server, true)?)
            .list_sessions(project, limit, cursor)
            .await
    }
    pub async fn session_get(&self, server: &str, id: &str) -> Result<SessionSummary> {
        SessionHistoryService::new(self.api(server, true)?)
            .get_session(id)
            .await
    }
    pub async fn session_turns(
        &self,
        server: &str,
        id: &str,
        limit: Option<i32>,
        cursor: Option<String>,
    ) -> Result<Page<ConversationTurn>> {
        SessionHistoryService::new(self.api(server, true)?)
            .list_turns(id, limit, cursor)
            .await
    }
    pub async fn resume_session(&self, server: &str, id: &str) -> Result<Conversation> {
        let session = self.session_get(server, id).await?;
        self.conversations.open_session(server, &session)
    }
    pub fn new(
        repository: Arc<dyn Repository>,
        credentials: Arc<dyn CredentialFactory>,
        clients: Arc<dyn ClientFactory>,
        observer: Arc<dyn RunObserver>,
        notifications: Arc<dyn NotificationPort>,
    ) -> Result<Self> {
        Ok(Self {
            conversations: ConversationService::new(repository)?,
            runs: Arc::new(RunManager::new(
                observer,
                notifications,
                Duration::from_secs(1),
            )),
            vault: Mutex::new(None),
            credentials,
            clients,
            submitting: Mutex::new(HashSet::new()),
        })
    }
    pub fn unlock(&self, password: Secret) -> Result<()> {
        let mut vault = self.vault.lock().unwrap();
        if vault.is_some() {
            return Ok(());
        }
        *vault = Some(self.credentials.unlock(password)?);
        Ok(())
    }
    pub fn unlocked(&self) -> bool {
        self.vault.lock().unwrap().is_some()
    }
    pub fn servers(&self) -> Result<Vec<ServerView>> {
        let data = self.conversations.store.snapshot();
        let vault = self.vault.lock().unwrap();
        data.servers
            .into_iter()
            .map(|s| {
                Ok(ServerView {
                    has_credential: match &*vault {
                        Some(v) => v.exists(&s.credential_ref)?,
                        None => false,
                    },
                    id: s.id,
                    name: s.name,
                    base_url: s.base_url,
                })
            })
            .collect()
    }
    fn profile(&self, id: &str) -> Result<ServerProfile> {
        self.conversations
            .store
            .snapshot()
            .servers
            .into_iter()
            .find(|s| s.id == id)
            .ok_or(AppError::NotFound)
    }
    fn busy_server(&self, id: &str) -> bool {
        !self.submitting.lock().unwrap().is_empty()
            || self.runs.active().iter().any(|r| r.server_id == id)
    }
    pub fn save_server(&self, id: Option<&str>, name: &str, url: &str) -> Result<String> {
        let mut profile = ServerProfile::new(name, url)?;
        if let Some(id) = id {
            if self.busy_server(id) {
                return Err(AppError::Busy);
            }
            let old = self.profile(id)?;
            // A server identity owns its sessions; changing origin would silently move sessions.
            if old.base_url != profile.base_url
                && self
                    .conversations
                    .list()
                    .iter()
                    .any(|c| c.server_profile_id == id)
            {
                return Err(AppError::SessionProjectConflict);
            }
            profile.id = old.id;
            profile.credential_ref = old.credential_ref;
        }
        self.conversations.store.transact(|data| {
            data.servers.retain(|s| s.id != profile.id);
            let id = profile.id.clone();
            data.servers.push(profile);
            if data.selected_server.is_none() {
                data.selected_server = Some(id.clone());
            }
            Ok(id)
        })
    }
    pub fn select_server(&self, id: &str) -> Result<()> {
        self.profile(id)?;
        self.conversations.store.transact(|data| {
            data.selected_server = Some(id.into());
            Ok(())
        })
    }
    pub fn remove_server(&self, id: &str) -> Result<()> {
        if self.busy_server(id) {
            return Err(AppError::Busy);
        }
        if self
            .conversations
            .list()
            .iter()
            .any(|c| c.server_profile_id == id)
        {
            return Err(AppError::Busy);
        }
        self.delete_credential(id)?;
        self.conversations.store.transact(|data| {
            data.servers.retain(|s| s.id != id);
            if data.selected_server.as_deref() == Some(id) {
                data.selected_server = data.servers.first().map(|s| s.id.clone());
            }
            Ok(())
        })
    }
    pub fn set_credential(&self, id: &str, secret: Secret) -> Result<()> {
        let profile = self.profile(id)?;
        self.vault
            .lock()
            .unwrap()
            .as_mut()
            .ok_or(AppError::VaultLocked)?
            .set(&profile.credential_ref, secret)
    }
    pub fn delete_credential(&self, id: &str) -> Result<()> {
        let profile = self.profile(id)?;
        self.vault
            .lock()
            .unwrap()
            .as_mut()
            .ok_or(AppError::VaultLocked)?
            .delete(&profile.credential_ref)
    }
    pub fn api(&self, id: &str, authenticated: bool) -> Result<Arc<dyn ReiClient>> {
        let profile = self.profile(id)?;
        let credential = if authenticated {
            Some(
                self.vault
                    .lock()
                    .unwrap()
                    .as_ref()
                    .ok_or(AppError::VaultLocked)?
                    .get(&profile.credential_ref)?,
            )
        } else {
            None
        };
        self.clients.create(&profile, credential)
    }
    pub async fn test_server(&self, id: &str) -> Result<ConnectionView> {
        let reachable = match self.api(id, false) {
            Ok(api) => api.health().await,
            Err(error) => return Ok(ConnectionView::failed(id, error, false)),
        };
        if let Err(error) = reachable {
            let responded = matches!(
                error,
                AppError::AuthenticationFailed
                    | AppError::PermissionDenied
                    | AppError::HealthAuthenticationRequired
                    | AppError::EndpointNotFound
                    | AppError::HttpRedirect
                    | AppError::RequestRejected
                    | AppError::RateLimited
                    | AppError::UnexpectedServerError
            );
            return Ok(ConnectionView::failed(id, error, responded));
        }
        let authenticated = match self.api(id, true) {
            Ok(api) => api.projects().await.map(|_| ()),
            Err(e) => Err(e),
        };
        Ok(ConnectionView {
            server_id: id.into(),
            state: match authenticated {
                Ok(_) => ConnectionState::Connected,
                Err(AppError::AuthenticationFailed | AppError::VaultLocked) => {
                    ConnectionState::AuthFailed
                }
                Err(_) => ConnectionState::ConnectionFailed,
            },
            reachable: true,
            authenticated: authenticated.is_ok(),
            error: authenticated.err(),
        })
    }
    pub async fn submit(&self, id: &str, message: &str) -> Result<RunView> {
        self.submit_mode(id, message, RunMode::Exclusive).await
    }
    pub async fn intervene(&self, server: &str, run: &str, message: &str) -> Result<()> {
        if message.trim().is_empty() || message.chars().count() > 16384 {
            return Err(AppError::InvalidInput);
        }
        let owner = self.runs.get(server, run)?;
        if owner.status.terminal() {
            return Err(AppError::Conflict);
        }
        self.api(server, true)?
            .input(run, &owner.project_id, &owner.session_id, message)
            .await
    }
    pub async fn submit_mode(&self, id: &str, message: &str, mode: RunMode) -> Result<RunView> {
        if message.trim().is_empty() {
            return Err(AppError::InvalidInput);
        }
        {
            let mut submitting = self.submitting.lock().unwrap();
            if submitting.contains(id) {
                return Err(AppError::Busy);
            }
            submitting.insert(id.into());
        }
        // RAII releases the reservation even if the invoking future is cancelled.
        let _reservation = Submission {
            ids: &self.submitting,
            id: id.into(),
        };
        // Repair a previously accepted session whose metadata write failed before
        // permitting another POST. Never silently create a replacement session.
        if let Some(run) = self.runs.all().iter().find(|r| r.conversation_id == id) {
            self.conversations
                .record_turn(id, &run.session_id, &run.prompt)?;
        }
        let c = self.conversations.get(id)?;
        let api = self.api(&c.server_profile_id, true)?;
        let target = match &c.session_id {
            Some(session) => {
                let summary = SessionHistoryService::new(api.clone())
                    .get_session(session)
                    .await?;
                self.conversations
                    .open_session(&c.server_profile_id, &summary)?;
                ConversationTarget::Existing(summary)
            }
            None => ConversationTarget::New {
                project_id: c.project_id.clone(),
            },
        };
        let receipt = match api
            .chat_mode(target.project_id(), target.session_id(), message, mode)
            .await
        {
            Err(AppError::SessionProjectConflict) => {
                if let Some(session) = target.session_id() {
                    if let Ok(summary) = api.get_session(session).await {
                        let _ = self
                            .conversations
                            .open_session(&c.server_profile_id, &summary);
                    }
                }
                return Err(AppError::SessionProjectConflict);
            }
            result => result?,
        };
        if receipt.run_id.is_empty() || receipt.session_id.is_empty() || receipt.turn_id.is_empty()
        {
            return Err(AppError::InvalidResponse);
        }
        if target
            .session_id()
            .is_some_and(|id| id != receipt.session_id)
        {
            return Err(AppError::InvalidResponse);
        }
        let run_id = receipt.run_id.clone();
        let session = receipt.session_id.clone();
        let mut projection = Projection::new(
            &c.server_profile_id,
            id,
            target.project_id(),
            receipt,
            message,
        );
        projection.mode = mode;
        self.runs.register(projection)?;
        // An accepted run must remain trackable even if metadata persistence fails.
        let persisted = self.conversations.record_turn(id, &session, message);
        self.runs.subscribe(&c.server_profile_id, &run_id, api)?;
        persisted?;
        // Refresh once after acceptance; failure must not make an accepted POST look retryable.
        if let Ok(summary) = self.session_get(&c.server_profile_id, &session).await {
            let _ = self
                .conversations
                .open_session(&c.server_profile_id, &summary);
        }
        self.runs.get(&c.server_profile_id, &run_id)
    }
    pub fn delete_conversation(&self, id: &str) -> Result<()> {
        if self.submitting.lock().unwrap().contains(id)
            || self.runs.active().iter().any(|r| r.conversation_id == id)
        {
            return Err(AppError::Busy);
        }
        self.conversations.delete(id)
    }
    pub fn notifications(&self, enabled: bool) -> Result<()> {
        self.conversations.store.transact(|data| {
            data.notifications = enabled;
            Ok(())
        })
    }
}
struct Submission<'a> {
    ids: &'a Mutex<HashSet<String>>,
    id: String,
}
impl Drop for Submission<'_> {
    fn drop(&mut self) {
        self.ids.lock().unwrap().remove(&self.id);
    }
}
