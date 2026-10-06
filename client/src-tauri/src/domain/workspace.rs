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
    ActivityCoachingSettings,
    ActivityCoachingConfigure {
        expected_revision: i64,
        settings: ActivityCoachingSettings,
    },
    ActivityCoachingEnabled {
        expected_revision: i64,
        enabled: bool,
    },
    ActivityAnalysis {
        period: String,
        date: Option<String>,
    },
    Schedules {
        project_id: String,
    },
    Schedule {
        project_id: String,
        id: String,
    },
    ScheduleHistory {
        project_id: String,
        id: String,
    },
    ScheduleActivate {
        project_id: String,
        id: String,
    },
    ScheduleCancel {
        project_id: String,
        id: String,
    },
    ScheduleReconcile {
        project_id: String,
        id: String,
        expected_run_id: String,
        acknowledge_uncertain_side_effects: bool,
    },
    Goals {
        project_id: String,
    },
    Goal {
        project_id: String,
        id: String,
    },
    GoalHistory {
        project_id: String,
        id: String,
    },
    GoalVerify {
        project_id: String,
        id: String,
    },
    GoalRun {
        project_id: String,
        id: String,
    },
    GoalCancel {
        project_id: String,
        id: String,
    },
    GoalReconcile {
        project_id: String,
        id: String,
        expected_run_id: String,
        acknowledge_uncertain_side_effects: bool,
    },
    Dependencies {
        project_id: String,
    },
    DependencyAnswer {
        project_id: String,
        id: String,
        expected_version: u64,
        answer: String,
    },
    Checkpoints {
        project_id: String,
    },
    Checkpoint {
        project_id: String,
        task_id: String,
    },
    CheckpointInspect {
        project_id: String,
        task_id: String,
    },
    CheckpointAbandon {
        project_id: String,
        task_id: String,
    },
    Attention {
        project_id: String,
    },
    AttentionAck {
        project_id: String,
        id: String,
    },
    Approvals {
        project_id: String,
    },
    ApprovalDecision {
        project_id: String,
        id: String,
        approved: bool,
    },
    WorkContext {
        project_id: String,
    },
    WorkContextHistory {
        project_id: String,
    },
    WorkContextUpdate {
        session_id: String,
    },
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

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ActivityCoachingSettings {
    pub enabled: bool,
    pub categories: Vec<String>,
    pub target_share: f64,
    pub minimum_observed_minutes: i32,
    pub minimum_coverage: f64,
    pub maximum_unknown_share: f64,
    pub cooldown_days: i32,
}
impl ActivityCoachingSettings {
    pub fn valid(&self) -> bool {
        !self.categories.is_empty()
            && self.categories.len() <= 11
            && self.categories.iter().all(|c| {
                matches!(
                    c.as_str(),
                    "development"
                        | "research"
                        | "documentation"
                        | "communication"
                        | "social"
                        | "media"
                        | "shopping"
                        | "gaming"
                        | "monitoring"
                        | "navigation"
                        | "idle"
                )
            })
            && self
                .categories
                .iter()
                .collect::<std::collections::HashSet<_>>()
                .len()
                == self.categories.len()
            && self.target_share.is_finite()
            && self.target_share > 0.0
            && self.target_share <= 1.0
            && (1..=44640).contains(&self.minimum_observed_minutes)
            && self.minimum_coverage.is_finite()
            && self.minimum_coverage > 0.0
            && self.minimum_coverage <= 1.0
            && self.maximum_unknown_share.is_finite()
            && (0.0..=1.0).contains(&self.maximum_unknown_share)
            && (1..=366).contains(&self.cooldown_days)
    }
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

#[derive(Debug, Clone)]
pub struct CheckpointReceipt {
    pub task_id: String,
    pub checkpoint_revision: u64,
    pub snapshot: super::RunSnapshot,
}
