use super::*;
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskReference {
    pub kind: String,
    pub id: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskProgress {
    pub completed: u32,
    pub total: u32,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManagedTask {
    pub id: String,
    pub kind: String,
    pub source_id: String,
    pub project_id: String,
    pub session_id: Option<String>,
    pub run_id: Option<String>,
    pub status: String,
    pub mode: Option<RunMode>,
    pub started_at: Option<String>,
    pub updated_at: Option<String>,
    pub waiting_reason: Option<String>,
    pub error_summary: Option<String>,
    pub progress: Option<TaskProgress>,
    pub results: Vec<TaskReference>,
    pub goal_id: Option<String>,
    pub dependency_ids: Vec<String>,
    pub scheduler_id: Option<String>,
    pub parent_id: Option<String>,
    pub child_ids: Vec<String>,
    pub checkpoint_task_id: Option<String>,
    pub cancel_supported: bool,
    pub resume_supported: bool,
    pub input_supported: bool,
    #[serde(default)]
    pub suspend_supported: bool,
    pub revision: u64,
}
impl ManagedTask {
    pub fn validate(&self) -> Result<()> {
        if !task_id(&self.id)
            || self.kind.is_empty()
            || self.kind.len() > 32
            || self.source_id.is_empty()
            || self.source_id.len() > 128
            || self.project_id.is_empty()
            || self.project_id.len() > 128
            || self
                .session_id
                .as_ref()
                .is_some_and(|id| id.is_empty() || id.len() > 1024)
            || self.status.is_empty()
            || self.status.len() > 40
            || !self
                .status
                .chars()
                .all(|c| c.is_ascii_uppercase() || c == '_')
            || self.revision > 9_007_199_254_740_991
            || self.results.len() > 512
            || self.child_ids.len() > 512
            || self.dependency_ids.len() > 512
            || self
                .progress
                .as_ref()
                .is_some_and(|p| p.completed > p.total || p.total > 1_000_000)
            || self.results.iter().any(|r| {
                r.kind.is_empty() || r.kind.len() > 64 || r.id.is_empty() || r.id.len() > 256
            })
            || self.child_ids.iter().any(|id| !task_id(id))
            || self
                .dependency_ids
                .iter()
                .any(|id| id.is_empty() || id.len() > 128)
            || self.parent_id.as_ref().is_some_and(|id| !task_id(id))
            || self.waiting_reason.as_ref().is_some_and(|s| s.len() > 8192)
            || self.error_summary.as_ref().is_some_and(|s| s.len() > 8192)
        {
            return Err(AppError::InvalidResponse);
        }
        for date in [&self.started_at, &self.updated_at].into_iter().flatten() {
            timestamp(date)?;
        }
        Ok(())
    }
    pub fn owned(&self, project: &str, session: Option<&str>, id: Option<&str>) -> Result<()> {
        self.validate()?;
        if self.project_id != project
            || self.session_id.as_deref() != session
            || id.is_some_and(|id| id != self.id)
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(())
    }
}
pub fn task_id(id: &str) -> bool {
    id.len() <= 256
        && id.split_once(':').is_some_and(|(kind, key)| {
            matches!(
                kind,
                "run" | "goal" | "dependency" | "schedule" | "subagent"
            ) && !key.is_empty()
                && key
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        })
}
#[derive(Debug, Clone)]
pub struct TaskQuery {
    pub project_id: Option<String>,
    pub session_id: Option<String>,
    pub page: HistoryQuery,
}
impl TaskQuery {
    pub fn new(
        project: Option<String>,
        session: Option<String>,
        limit: Option<i32>,
        cursor: Option<String>,
    ) -> Result<Self> {
        if project
            .as_ref()
            .is_some_and(|p| p.is_empty() || p.len() > 128)
            || session
                .as_ref()
                .is_some_and(|s| s.is_empty() || s.len() > 1024)
        {
            return Err(AppError::InvalidInput);
        }
        Ok(Self {
            project_id: project,
            session_id: session,
            page: HistoryQuery::new(limit, cursor)?,
        })
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskPage {
    pub items: Vec<ManagedTask>,
    pub next_cursor: Option<String>,
}
impl TaskPage {
    pub fn validate(&self, query: &TaskQuery) -> Result<()> {
        if self.items.len() > query.page.limit as usize
            || self
                .next_cursor
                .as_ref()
                .is_some_and(|c| c.is_empty() || c.len() > 4096)
        {
            return Err(AppError::InvalidResponse);
        }
        let mut ids = std::collections::HashSet::new();
        for task in &self.items {
            task.validate()?;
            if !ids.insert(&task.id)
                || query
                    .project_id
                    .as_ref()
                    .is_some_and(|p| p != &task.project_id)
                || query
                    .session_id
                    .as_ref()
                    .is_some_and(|s| task.session_id.as_ref() != Some(s))
            {
                return Err(AppError::InvalidResponse);
            }
        }
        Ok(())
    }
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum TaskAction {
    Cancel,
    Suspend,
    Resume,
    Input,
}
