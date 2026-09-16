use super::*;
use serde::Deserialize;

impl HttpReiClient {
    pub(super) fn history_request(
        &self,
        path: &str,
        query: &HistoryQuery,
    ) -> Result<reqwest::RequestBuilder> {
        HistoryQuery::new(Some(query.limit.into()), query.cursor.clone())?;
        let request = self
            .request(Method::GET, path, true)?
            .query(&[("limit", query.limit)]);
        Ok(if let Some(cursor) = &query.cursor {
            request.query(&[("cursor", cursor)])
        } else {
            request
        })
    }
}
pub(super) fn history_error(error: AppError, query: &HistoryQuery) -> AppError {
    if error == AppError::InvalidInput && query.cursor.is_some() {
        AppError::InvalidCursor
    } else {
        error
    }
}
pub(super) fn session_path(id: &str, suffix: &str) -> Result<String> {
    if id.is_empty() || id == "." || id == ".." {
        return Err(AppError::InvalidInput);
    }
    let mut url =
        url::Url::parse("http://localhost/api/v1/sessions").map_err(|_| AppError::InvalidInput)?;
    url.path_segments_mut()
        .map_err(|_| AppError::InvalidInput)?
        .push(id);
    Ok(format!("{}{suffix}", url.path()))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SessionResponse {
    session_id: String,
    project_id: String,
    title: String,
    created_at: String,
    updated_at: String,
}
impl SessionResponse {
    pub(super) fn into_domain(self) -> Result<SessionSummary> {
        SessionSummary::from_wire(
            &self.session_id,
            &self.project_id,
            self.title,
            &self.created_at,
            &self.updated_at,
        )
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SessionPageResponse {
    items: Vec<SessionResponse>,
    next_cursor: Option<String>,
}
impl SessionPageResponse {
    pub(super) fn into_domain(self, limit: u16) -> Result<Page<SessionSummary>> {
        if self.items.len() > usize::from(limit) {
            return Err(AppError::InvalidResponse);
        }
        Ok(Page {
            items: self
                .items
                .into_iter()
                .map(SessionResponse::into_domain)
                .collect::<Result<_>>()?,
            next_cursor: self.next_cursor,
        })
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TurnResponse {
    turn_id: String,
    run_id: String,
    user_message: String,
    assistant_message: Option<String>,
    created_at: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct TurnPageResponse {
    session_id: String,
    items: Vec<TurnResponse>,
    next_cursor: Option<String>,
}
impl TurnPageResponse {
    pub(super) fn into_domain(self, session: &str, limit: u16) -> Result<Page<ConversationTurn>> {
        if self.session_id != session || self.items.len() > usize::from(limit) {
            return Err(AppError::InvalidResponse);
        }
        let items = self
            .items
            .into_iter()
            .map(|turn| {
                if turn.run_id.is_empty() || turn.turn_id != turn.run_id {
                    return Err(AppError::InvalidResponse);
                }
                Ok(ConversationTurn {
                    run_id: turn.run_id,
                    user_message: turn.user_message,
                    assistant_message: turn.assistant_message,
                    created_at: timestamp(&turn.created_at)?,
                })
            })
            .collect::<Result<_>>()?;
        Ok(Page {
            items,
            next_cursor: self.next_cursor,
        })
    }
}
