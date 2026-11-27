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
pub enum LlmSupport { Supported, Unsupported, Unknown }
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LlmModels {
    pub endpoint: String,
    pub current_model: Option<String>,
    pub models: Vec<String>,
}
