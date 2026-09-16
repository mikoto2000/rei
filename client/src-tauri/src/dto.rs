//! UI boundary, independent of HTTP response schemas and non-serializable domain models.
use crate::domain::*;
use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionSummaryDto {
    session_id: String,
    project_id: String,
    title: String,
    created_at: String,
    updated_at: String,
}
impl From<SessionSummary> for SessionSummaryDto {
    fn from(s: SessionSummary) -> Self {
        Self {
            session_id: s.session_id,
            project_id: s.project_id,
            title: s.title,
            created_at: s
                .created_at
                .to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true),
            updated_at: s
                .updated_at
                .to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true),
        }
    }
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConversationTurnDto {
    turn_id: String,
    run_id: String,
    user_message: String,
    assistant_message: Option<String>,
    created_at: String,
}
impl From<ConversationTurn> for ConversationTurnDto {
    fn from(t: ConversationTurn) -> Self {
        Self {
            turn_id: t.run_id.clone(),
            run_id: t.run_id,
            user_message: t.user_message,
            assistant_message: t.assistant_message,
            created_at: t
                .created_at
                .to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true),
        }
    }
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionPageDto {
    items: Vec<SessionSummaryDto>,
    next_cursor: Option<String>,
}
impl From<Page<SessionSummary>> for SessionPageDto {
    fn from(p: Page<SessionSummary>) -> Self {
        Self {
            items: p.items.into_iter().map(Into::into).collect(),
            next_cursor: p.next_cursor,
        }
    }
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TurnPageDto {
    items: Vec<ConversationTurnDto>,
    next_cursor: Option<String>,
}
impl From<Page<ConversationTurn>> for TurnPageDto {
    fn from(p: Page<ConversationTurn>) -> Self {
        Self {
            items: p.items.into_iter().map(Into::into).collect(),
            next_cursor: p.next_cursor,
        }
    }
}
