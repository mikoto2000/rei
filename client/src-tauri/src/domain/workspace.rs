use serde::{Deserialize, Serialize};
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(tag = "operation", rename_all = "camelCase", deny_unknown_fields)]
pub enum BackgroundOperation {
    Summary {
        url: String,
    },
    Image {
        prompt: String,
        size: Option<String>,
    },
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackgroundReceipt {
    pub run_id: String,
}

/// Explicit allowlist of server operations; never accepts an endpoint or filesystem path.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(
    tag = "operation",
    rename_all = "camelCase",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub enum WorkspaceOperation {
    Feeds,
    Feed {
        id: i64,
    },
    Skills,
    Skill {
        name: String,
    },
    Profile,
    Briefing,
    Search {
        query: String,
        vector_top_k: i32,
        web_top_k: i32,
        threshold: f64,
    },
    CreateFeed {
        url: String,
        display_name: Option<String>,
    },
    UpdateFeed {
        id: i64,
        display_name: Option<String>,
        enabled: Option<bool>,
    },
    DeleteFeed {
        id: i64,
    },
    Reminders,
    Reminder {
        id: i64,
    },
    CreateReminder {
        message: String,
        at: Option<String>,
        target: Option<String>,
        minutes_before: Option<i32>,
    },
    DeleteReminder {
        id: i64,
    },
    Interests {
        hours: i32,
    },
    CreateInterest {
        topic: String,
        reason: String,
        search_query: String,
        summary: String,
        source_urls: Vec<String>,
    },
    Memories,
    Memory {
        id: String,
    },
    CreateMemory {
        content: String,
        memory_type: String,
        scope: String,
        confidence: f64,
    },
    DeleteMemory {
        id: String,
    },
    ReloadSkills,
}

/// Client presentation model. Server DTOs are decoded and mapped in the API adapter.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceItem {
    pub id: Option<String>,
    pub title: String,
    pub fields: Vec<(String, String)>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkspaceResult {
    pub title: String,
    pub items: Vec<WorkspaceItem>,
}
