use super::{AppError, Result};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionSummary {
    pub session_id: String,
    pub project_id: String,
    pub title: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}
impl SessionSummary {
    pub fn from_wire(
        id: &str,
        project: &str,
        title: String,
        created: &str,
        updated: &str,
    ) -> Result<Self> {
        if id.is_empty() || project.is_empty() {
            return Err(AppError::InvalidResponse);
        }
        Ok(Self {
            session_id: id.into(),
            project_id: project.into(),
            title,
            created_at: timestamp(created)?,
            updated_at: timestamp(updated)?,
        })
    }
}
pub fn timestamp(value: &str) -> Result<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value)
        .map(|date| date.with_timezone(&Utc))
        .map_err(|_| AppError::InvalidResponse)
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConversationTurn {
    pub run_id: String,
    pub user_message: String,
    pub assistant_message: Option<String>,
    pub source: Option<String>,
    pub source_id: Option<String>,
    pub metadata: std::collections::BTreeMap<String, String>,
    pub created_at: DateTime<Utc>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Page<T> {
    pub items: Vec<T>,
    pub next_cursor: Option<String>,
}
#[derive(Debug, Clone)]
pub struct HistoryQuery {
    pub limit: u16,
    pub cursor: Option<String>,
}
impl HistoryQuery {
    pub fn new(limit: Option<i32>, cursor: Option<String>) -> Result<Self> {
        let limit = limit.unwrap_or(50);
        if !(1..=100).contains(&limit) {
            return Err(AppError::InvalidLimit);
        }
        Ok(Self {
            limit: limit as u16,
            cursor,
        })
    }
}
#[derive(Debug, Clone)]
pub enum ConversationTarget {
    New { project_id: String },
    Existing(SessionSummary),
}
impl ConversationTarget {
    pub fn project_id(&self) -> &str {
        match self {
            Self::New { project_id } => project_id,
            Self::Existing(session) => &session.project_id,
        }
    }
    pub fn session_id(&self) -> Option<&str> {
        match self {
            Self::New { .. } => None,
            Self::Existing(session) => Some(&session.session_id),
        }
    }
}
