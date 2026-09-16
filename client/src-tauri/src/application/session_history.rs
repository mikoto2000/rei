use crate::{domain::*, ports::ReiClient};
use std::sync::Arc;

/// Server metadata is authoritative; this service never saves title/project/history locally.
pub struct SessionHistoryService {
    api: Arc<dyn ReiClient>,
}
impl SessionHistoryService {
    pub fn new(api: Arc<dyn ReiClient>) -> Self {
        Self { api }
    }
    pub async fn list_sessions(
        &self,
        project: Option<&str>,
        limit: Option<i32>,
        cursor: Option<String>,
    ) -> Result<Page<SessionSummary>> {
        self.api
            .list_sessions(project, HistoryQuery::new(limit, cursor)?)
            .await
    }
    pub async fn get_session(&self, id: &str) -> Result<SessionSummary> {
        self.api.get_session(id).await
    }
    pub async fn list_turns(
        &self,
        id: &str,
        limit: Option<i32>,
        cursor: Option<String>,
    ) -> Result<Page<ConversationTurn>> {
        self.api
            .list_session_turns(id, HistoryQuery::new(limit, cursor)?)
            .await
    }
}
