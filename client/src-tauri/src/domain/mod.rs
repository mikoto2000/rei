use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum AppError {
    ServerUnreachable,
    AuthenticationFailed,
    ProjectNotFound,
    SessionNotFound,
    SessionProjectConflict,
    RunNotFound,
    ReplayGap,
    StreamDisconnected,
    RunFailed,
    Cancelled,
    InvalidInput,
    InvalidResponse,
    Storage,
    VaultLocked,
    Busy,
    NotFound,
}
pub type Result<T> = std::result::Result<T, AppError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StreamState {
    Connecting,
    Connected,
    Reconnecting,
    Closed,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    AuthFailed,
    ServerUnreachable,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Project {
    pub id: String,
    pub name: String,
    pub path: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatReceipt {
    pub run_id: String,
    pub session_id: String,
    pub turn_id: String,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RunStatus {
    Queued,
    Running,
    Completed,
    Failed,
    Cancelled,
}
impl RunStatus {
    pub fn terminal(self) -> bool {
        matches!(self, Self::Completed | Self::Failed | Self::Cancelled)
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunSnapshot {
    pub run_id: String,
    pub session_id: String,
    pub turn_id: String,
    pub project_id: String,
    pub status: RunStatus,
    pub failure: Option<serde_json::Value>,
}

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AppData {
    pub conversations: Vec<Conversation>,
    pub servers: Vec<ServerProfile>,
    pub selected_server: Option<String>,
    pub notifications: bool,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Conversation {
    pub local_id: String,
    pub server_profile_id: String,
    pub project_id: String,
    pub session_id: Option<String>,
    pub title: String,
    pub created_at: u64,
    pub last_accessed_at: u64,
}

#[derive(Clone)]
pub struct Secret(Zeroizing<String>);
impl Secret {
    pub fn new(value: String) -> Self {
        Self(Zeroizing::new(value))
    }
    pub fn expose(&self) -> &str {
        &self.0
    }
}
impl std::fmt::Debug for Secret {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("[REDACTED]")
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerProfile {
    pub id: String,
    pub name: String,
    pub base_url: String,
    pub credential_ref: String,
}
impl ServerProfile {
    pub fn new(name: &str, base_url: &str) -> Result<Self> {
        let url = url::Url::parse(base_url).map_err(|_| AppError::InvalidInput)?;
        if name.trim().is_empty()
            || !matches!(url.scheme(), "http" | "https")
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
        {
            return Err(AppError::InvalidInput);
        }
        let id = uuid::Uuid::new_v4().to_string();
        Ok(Self {
            credential_ref: id.clone(),
            id,
            name: name.trim().into(),
            base_url: url.as_str().trim_end_matches('/').into(),
        })
    }
}

#[derive(Clone, Copy)]
pub enum Operation {
    Health,
    Projects,
    Chat,
    Run,
    Stream,
}
pub fn http_error(status: u16, op: Operation) -> AppError {
    match (status, op) {
        (401 | 403, _) => AppError::AuthenticationFailed,
        (404, Operation::Chat) => AppError::SessionNotFound,
        (404, Operation::Projects) => AppError::ProjectNotFound,
        (404, Operation::Run | Operation::Stream) => AppError::RunNotFound,
        (409, Operation::Chat) => AppError::SessionProjectConflict,
        (409, Operation::Stream) => AppError::ReplayGap,
        (400 | 422, _) => AppError::InvalidInput,
        _ => AppError::ServerUnreachable,
    }
}
