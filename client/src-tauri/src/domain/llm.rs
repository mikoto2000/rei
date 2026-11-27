use super::*;
use std::collections::BTreeMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LlmEndpoint {
    pub profile: LlmProfile,
    pub active: bool,
    pub reasoning_replay: Option<String>,
    pub authentication_state: String,
    pub unavailable_reason: Option<String>,
    pub generation_settings: BTreeMap<String, serde_json::Value>,
    pub unapplied_settings: BTreeMap<String, String>,
    pub diagnostics: Vec<String>,
    pub selection_scope: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LlmProfile {
    pub id: String,
    pub model: Option<String>,
    pub provider: String,
    pub api: String,
    pub authentication: String,
    pub account_ref: Option<String>,
    pub usage_path: Option<String>,
    pub capabilities: LlmCapabilities,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LlmCapabilities {
    pub route: BTreeMap<String, LlmSupport>,
    pub server: BTreeMap<String, LlmSupport>,
    pub model: BTreeMap<String, LlmSupport>,
    pub effective: BTreeMap<String, LlmSupport>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum LlmSupport {
    Supported,
    Unsupported,
    Unknown,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LlmModels {
    pub endpoint: String,
    pub current_model: Option<String>,
    pub models: Vec<String>,
}

pub fn llm_endpoint_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value.as_bytes()[0].is_ascii_alphanumeric()
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"_.-".contains(&c))
}
pub fn llm_model_name(value: &str) -> bool {
    !value.trim().is_empty()
        && value.chars().count() <= 1024
        && !value.chars().any(char::is_control)
}
impl LlmEndpoint {
    pub fn validate(&self) -> Result<()> {
        if !llm_endpoint_id(&self.profile.id)
            || self
                .profile
                .model
                .as_deref()
                .is_some_and(|m| !llm_model_name(m))
            || !matches!(
                self.profile.provider.as_str(),
                "openai-compatible" | "openai-api" | "chatgpt-plan"
            )
            || !matches!(self.profile.api.as_str(), "chat-completions" | "responses")
            || !matches!(
                self.profile.authentication.as_str(),
                "none" | "api-key" | "chatgpt-oauth" | "legacy" | "unconfigured"
            )
            || self.generation_settings.len() > 32
            || self.unapplied_settings.len() > 32
            || self.diagnostics.len() > 256
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(())
    }
}
impl LlmModels {
    pub fn validate(&self) -> Result<()> {
        if !llm_endpoint_id(&self.endpoint)
            || self.models.len() > 4096
            || self.models.iter().any(|m| !llm_model_name(m))
            || self
                .current_model
                .as_deref()
                .is_some_and(|m| !llm_model_name(m))
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(())
    }
}
