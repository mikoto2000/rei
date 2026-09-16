use crate::{domain::*, ports::*};
use async_trait::async_trait;
use futures_util::StreamExt;
use reqwest::{Client, Method, Response};
use serde::de::DeserializeOwned;
use std::time::Duration;
mod history;
use history::*;

/// Enforce mobile transport policy in Rust too: native HTTP can bypass WebView/OS rules.
pub fn validate_transport(base_url: &str, mobile: bool) -> Result<()> {
    let profile = ServerProfile::new("transport", base_url)?;
    if mobile && !profile.base_url.starts_with("https://") {
        return Err(AppError::InvalidInput);
    }
    Ok(())
}

pub struct HttpReiClient {
    client: Client,
    base_url: String,
    credential: Option<Secret>,
}
impl HttpReiClient {
    pub fn new(base_url: &str, credential: Option<Secret>) -> Result<Self> {
        validate_transport(
            base_url,
            cfg!(any(target_os = "android", target_os = "ios")),
        )?;
        let profile = ServerProfile::new("validation", base_url)?;
        let client = Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .build()
            .map_err(|_| AppError::InvalidInput)?;
        Ok(Self {
            client,
            base_url: profile.base_url,
            credential,
        })
    }
    fn request(
        &self,
        method: Method,
        path: &str,
        authenticated: bool,
    ) -> Result<reqwest::RequestBuilder> {
        let request = self
            .client
            .request(method, format!("{}{path}", self.base_url));
        if !authenticated {
            return Ok(request);
        }
        let secret = self
            .credential
            .as_ref()
            .ok_or(AppError::AuthenticationFailed)?;
        Ok(request.bearer_auth(secret.expose()))
    }
    async fn checked(request: reqwest::RequestBuilder, op: Operation) -> Result<Response> {
        let response = request
            .send()
            .await
            .map_err(|_| AppError::ServerUnreachable)?;
        if !response.status().is_success() {
            return Err(http_error(response.status().as_u16(), op));
        }
        Ok(response)
    }
    async fn json<T: DeserializeOwned>(
        request: reqwest::RequestBuilder,
        op: Operation,
    ) -> Result<T> {
        Self::checked(request.timeout(Duration::from_secs(30)), op)
            .await?
            .json()
            .await
            .map_err(|_| AppError::InvalidResponse)
    }
    fn run_path(run: &str, suffix: &str) -> Result<String> {
        if run.is_empty() || !run.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
            return Err(AppError::InvalidInput);
        }
        Ok(format!("/api/v1/runs/{run}{suffix}"))
    }
}
#[async_trait]
impl ReiClient for HttpReiClient {
    async fn list_sessions(
        &self,
        project: Option<&str>,
        query: HistoryQuery,
    ) -> Result<Page<SessionSummary>> {
        let request = self.history_request("/api/v1/sessions", &query)?;
        let request = if let Some(project) = project {
            request.query(&[("projectId", project)])
        } else {
            request
        };
        let response: SessionPageResponse = Self::json(request, Operation::Sessions)
            .await
            .map_err(|error| history_error(error, &query))?;
        response.into_domain(query.limit)
    }
    async fn get_session(&self, session: &str) -> Result<SessionSummary> {
        let response: SessionResponse = Self::json(
            self.request(Method::GET, &session_path(session, "")?, true)?,
            Operation::Session,
        )
        .await?;
        let model = response.into_domain()?;
        if model.session_id != session {
            return Err(AppError::InvalidResponse);
        }
        Ok(model)
    }
    async fn list_session_turns(
        &self,
        session: &str,
        query: HistoryQuery,
    ) -> Result<Page<ConversationTurn>> {
        let response: TurnPageResponse = Self::json(
            self.history_request(&session_path(session, "/turns")?, &query)?,
            Operation::Session,
        )
        .await
        .map_err(|error| history_error(error, &query))?;
        response.into_domain(session, query.limit)
    }
    async fn health(&self) -> Result<()> {
        Self::checked(
            self.request(Method::GET, "/actuator/health", false)?
                .timeout(Duration::from_secs(10)),
            Operation::Health,
        )
        .await?;
        Ok(())
    }
    async fn projects(&self) -> Result<Vec<Project>> {
        Self::json(
            self.request(Method::GET, "/api/v1/projects", true)?,
            Operation::Projects,
        )
        .await
    }
    async fn chat(
        &self,
        project: &str,
        session: Option<&str>,
        message: &str,
    ) -> Result<ChatReceipt> {
        let mut body = serde_json::json!({"projectId":project,"message":message});
        if let Some(session) = session {
            body["sessionId"] = session.into();
        }
        let response = Self::checked(
            self.request(Method::POST, "/api/v1/chat", true)?
                .json(&body)
                .timeout(Duration::from_secs(30)),
            Operation::Chat,
        )
        .await
        .map_err(|error| {
            if session.is_none() && error == AppError::SessionNotFound {
                AppError::ProjectNotFound
            } else {
                error
            }
        })?;
        if response.status() != reqwest::StatusCode::ACCEPTED {
            return Err(AppError::InvalidResponse);
        }
        response.json().await.map_err(|_| AppError::InvalidResponse)
    }
    async fn run(&self, run: &str) -> Result<RunSnapshot> {
        Self::json(
            self.request(Method::GET, &Self::run_path(run, "")?, true)?,
            Operation::Run,
        )
        .await
    }
    async fn cancel(&self, run: &str) -> Result<RunSnapshot> {
        Self::json(
            self.request(Method::POST, &Self::run_path(run, "/cancel")?, true)?,
            Operation::Run,
        )
        .await
    }
    async fn events(&self, run: &str, last: Option<u64>) -> Result<ByteStream> {
        let mut request = self
            .request(Method::GET, &Self::run_path(run, "/events")?, true)?
            .header("Accept", "text/event-stream");
        if let Some(last) = last {
            request = request.header("Last-Event-ID", last.to_string());
        }
        let response = tokio::time::timeout(
            Duration::from_secs(15),
            Self::checked(request, Operation::Stream),
        )
        .await
        .map_err(|_| AppError::StreamDisconnected)??;
        if !response
            .headers()
            .get("content-type")
            .and_then(|h| h.to_str().ok())
            .unwrap_or("")
            .starts_with("text/event-stream")
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(Box::pin(response.bytes_stream().map(|bytes| {
            bytes
                .map(|b| b.to_vec())
                .map_err(|_| AppError::StreamDisconnected)
        })))
    }
}
