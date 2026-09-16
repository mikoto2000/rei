use crate::{domain::*, ports::Repository};
use std::sync::{Arc, Mutex};

pub struct AppStore {
    data: Mutex<AppData>,
    repository: Arc<dyn Repository>,
}
impl AppStore {
    pub fn new(repository: Arc<dyn Repository>) -> Result<Self> {
        Ok(Self {
            data: Mutex::new(repository.load()?),
            repository,
        })
    }
    pub fn snapshot(&self) -> AppData {
        self.data.lock().unwrap().clone()
    }
    pub fn transact<T>(&self, change: impl FnOnce(&mut AppData) -> Result<T>) -> Result<T> {
        let mut current = self.data.lock().unwrap();
        let mut next = current.clone();
        let result = change(&mut next)?;
        self.repository.save(&next)?;
        *current = next;
        Ok(result)
    }
}
pub struct ConversationService {
    pub store: Arc<AppStore>,
}
impl ConversationService {
    pub fn new(repository: Arc<dyn Repository>) -> Result<Self> {
        Ok(Self {
            store: Arc::new(AppStore::new(repository)?),
        })
    }
    pub fn create(&self, server: &str, project: &str, _title: &str) -> Result<Conversation> {
        if project.is_empty() {
            return Err(AppError::InvalidInput);
        }
        self.store.transact(|data| {
            if !data.servers.iter().any(|s| s.id == server) {
                return Err(AppError::NotFound);
            }
            let now = now();
            let c = Conversation {
                local_id: uuid::Uuid::new_v4().to_string(),
                server_profile_id: server.into(),
                project_id: project.into(),
                session_id: None,
                title: "新しい会話".into(),
                created_at: now,
                last_accessed_at: now,
            };
            data.conversations.push(c.clone());
            Ok(c)
        })
    }
    pub fn get(&self, id: &str) -> Result<Conversation> {
        self.store
            .snapshot()
            .conversations
            .into_iter()
            .find(|c| c.local_id == id)
            .ok_or(AppError::NotFound)
    }
    pub fn open_session(&self, server: &str, session: &SessionSummary) -> Result<Conversation> {
        self.store.transact(|data| {
            if !data.servers.iter().any(|profile| profile.id == server) {
                return Err(AppError::NotFound);
            }
            let existing = data.conversations.iter_mut().find(|c| {
                c.server_profile_id == server
                    && c.session_id.as_deref() == Some(&session.session_id)
            });
            let c = if let Some(c) = existing {
                c
            } else {
                data.conversations.push(Conversation {
                    local_id: uuid::Uuid::new_v4().to_string(),
                    server_profile_id: server.into(),
                    project_id: session.project_id.clone(),
                    session_id: Some(session.session_id.clone()),
                    title: String::new(),
                    created_at: 0,
                    last_accessed_at: 0,
                });
                data.conversations.last_mut().unwrap()
            };
            c.project_id = session.project_id.clone();
            c.title = session.title.clone();
            c.created_at = session.created_at.timestamp_millis().max(0) as u64;
            c.last_accessed_at = session.updated_at.timestamp_millis().max(0) as u64;
            Ok(c.clone())
        })
    }
    pub fn list(&self) -> Vec<Conversation> {
        let mut list = self.store.snapshot().conversations;
        list.sort_by_key(|c| std::cmp::Reverse(c.last_accessed_at));
        list
    }
    pub fn attach_session(&self, id: &str, session: &str) -> Result<()> {
        if session.is_empty() {
            return Err(AppError::InvalidResponse);
        }
        self.store.transact(|data| {
            let c = data
                .conversations
                .iter_mut()
                .find(|c| c.local_id == id)
                .ok_or(AppError::NotFound)?;
            if c.session_id
                .as_deref()
                .is_some_and(|existing| existing != session)
            {
                return Err(AppError::SessionProjectConflict);
            }
            c.session_id = Some(session.into());
            Ok(())
        })
    }
    pub fn touch(&self, id: &str) -> Result<()> {
        self.store.transact(|data| {
            let c = data
                .conversations
                .iter_mut()
                .find(|c| c.local_id == id)
                .ok_or(AppError::NotFound)?;
            if c.session_id.is_none() {
                c.last_accessed_at = now();
            }
            Ok(())
        })
    }
    pub fn record_turn(&self, id: &str, session: &str, _prompt: &str) -> Result<()> {
        self.store.transact(|data| {
            let c = data
                .conversations
                .iter_mut()
                .find(|c| c.local_id == id)
                .ok_or(AppError::NotFound)?;
            if c.session_id.as_deref().is_some_and(|s| s != session) {
                return Err(AppError::SessionProjectConflict);
            }
            c.session_id = Some(session.into());
            Ok(())
        })
    }
    pub fn change_project(&self, id: &str, project: &str) -> Result<()> {
        if project.is_empty() {
            return Err(AppError::InvalidInput);
        }
        self.store.transact(|data| {
            let c = data
                .conversations
                .iter_mut()
                .find(|c| c.local_id == id)
                .ok_or(AppError::NotFound)?;
            if c.session_id.is_some() && c.project_id != project {
                return Err(AppError::SessionProjectConflict);
            }
            c.project_id = project.into();
            Ok(())
        })
    }
    pub fn continue_as_new(&self, id: &str) -> Result<Conversation> {
        let c = self.get(id)?;
        self.create(&c.server_profile_id, &c.project_id, &c.title)
    }
    pub fn delete(&self, id: &str) -> Result<()> {
        self.store.transact(|data| {
            data.conversations.retain(|c| c.local_id != id);
            Ok(())
        })
    }
}
fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
