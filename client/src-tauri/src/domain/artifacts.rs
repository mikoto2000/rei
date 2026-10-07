use super::*;
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeliveryArtifact {
    pub artifact_id: String,
    pub owner: String,
    pub project_id: String,
    pub session_id: Option<String>,
    pub run_id: Option<String>,
    pub task_id: Option<String>,
    pub media_type: String,
    pub filename: String,
    pub size: u64,
    pub sha256: String,
    pub created_at: String,
    pub expires_at: Option<String>,
    pub storage_reference: String,
    pub status: String,
}
pub fn artifact_id(id: &str) -> bool {
    uuid::Uuid::parse_str(id).is_ok_and(|value| value.to_string() == id)
}
impl DeliveryArtifact {
    pub fn validate(&self) -> Result<()> {
        if !artifact_id(&self.artifact_id)
            || self.project_id.is_empty()
            || self.project_id.len() > 128
            || !matches!(self.owner.as_str(), "RUN" | "SESSION" | "PROJECT")
            || self
                .session_id
                .as_ref()
                .is_some_and(|id| id.is_empty() || id.len() > 1024)
            || self
                .run_id
                .as_ref()
                .is_some_and(|id| id.is_empty() || id.len() > 128)
            || self
                .task_id
                .as_ref()
                .is_some_and(|id| id.is_empty() || id.len() > 256)
            || self.size > 32 * 1024 * 1024
            || self.sha256.len() != 64
            || !self
                .sha256
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
            || self.filename.is_empty()
            || self.filename.len() > 255
            || self.filename.encode_utf16().count() > 128
            || matches!(self.filename.as_str(), "." | "..")
            || self
                .filename
                .chars()
                .any(|c| c.is_control() || "/\\:".contains(c))
            || !matches!(
                self.media_type.as_str(),
                "text/plain"
                    | "text/markdown"
                    | "application/json"
                    | "image/png"
                    | "image/jpeg"
                    | "application/pdf"
                    | "application/octet-stream"
            )
            || !matches!(
                self.status.as_str(),
                "PUBLISHING"
                    | "AVAILABLE"
                    | "UNKNOWN"
                    | "MISSING"
                    | "STALE"
                    | "EXPIRED"
                    | "DELETED"
                    | "DELETING"
            )
            || self.storage_reference != format!("artifact:{}", self.artifact_id)
        {
            return Err(AppError::InvalidResponse);
        }
        timestamp(&self.created_at)?;
        if let Some(expires) = &self.expires_at {
            if timestamp(expires)? < timestamp(&self.created_at)? {
                return Err(AppError::InvalidResponse);
            }
        }
        Ok(())
    }
    pub fn owned(&self, project: &str, session: Option<&str>, id: &str) -> Result<()> {
        self.validate()?;
        if self.project_id != project
            || self.session_id.as_deref() != session
            || self.artifact_id != id
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(())
    }
    pub fn content_valid(&self, bytes: &[u8]) -> Result<()> {
        use sha2::{Digest, Sha256};
        self.validate()?;
        if self.status != "AVAILABLE"
            || bytes.len() as u64 != self.size
            || format!("{:x}", Sha256::digest(bytes)) != self.sha256
        {
            return Err(AppError::InvalidResponse);
        }
        if self.expires_at.as_ref().is_some_and(|value| {
            timestamp(value).is_ok_and(|expires| {
                expires <= chrono::DateTime::<chrono::Utc>::from(std::time::SystemTime::now())
            })
        }) {
            return Err(AppError::Conflict);
        }
        Ok(())
    }
}
#[derive(Debug, Clone)]
pub struct ArtifactQuery {
    pub project_id: Option<String>,
    pub session_id: Option<String>,
    pub run_id: Option<String>,
    pub page: HistoryQuery,
}
impl ArtifactQuery {
    pub fn new(
        project: Option<String>,
        session: Option<String>,
        run: Option<String>,
        limit: Option<i32>,
        cursor: Option<String>,
    ) -> Result<Self> {
        if project
            .as_ref()
            .is_some_and(|p| p.is_empty() || p.len() > 128)
            || session
                .as_ref()
                .is_some_and(|s| s.is_empty() || s.len() > 1024)
            || run.as_ref().is_some_and(|r| r.is_empty() || r.len() > 128)
        {
            return Err(AppError::InvalidInput);
        }
        Ok(Self {
            project_id: project,
            session_id: session,
            run_id: run,
            page: HistoryQuery::new(limit, cursor)?,
        })
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtifactPage {
    pub items: Vec<DeliveryArtifact>,
    pub next_cursor: Option<String>,
}
impl ArtifactPage {
    pub fn validate(&self, query: &ArtifactQuery) -> Result<()> {
        if self.items.len() > usize::from(query.page.limit)
            || self
                .next_cursor
                .as_ref()
                .is_some_and(|c| c.is_empty() || c.len() > 4096)
        {
            return Err(AppError::InvalidResponse);
        }
        let mut ids = std::collections::HashSet::new();
        for item in &self.items {
            item.validate()?;
            if !ids.insert(&item.artifact_id)
                || query
                    .project_id
                    .as_ref()
                    .is_some_and(|id| &item.project_id != id)
                || query
                    .session_id
                    .as_ref()
                    .is_some_and(|id| item.session_id.as_ref() != Some(id))
                || query
                    .run_id
                    .as_ref()
                    .is_some_and(|id| item.run_id.as_ref() != Some(id))
            {
                return Err(AppError::InvalidResponse);
            }
        }
        Ok(())
    }
}
