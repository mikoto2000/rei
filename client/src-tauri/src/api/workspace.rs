use super::*;
use serde::Deserialize;
use serde_json::json;
use std::collections::BTreeMap;
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CoachingSettingsDto {
    schema_version: u32,
    scope: String,
    revision: i64,
    settings: ActivityCoachingSettings,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ActivityAnalysisDto {
    schema_version: u32,
    scope: String,
    period: String,
    anchor_date: String,
    zone: String,
    partial: bool,
    report: String,
}
#[derive(Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct ProfileDto {
    total: i64,
    first: Option<String>,
    last: Option<String>,
    counts_by_type: BTreeMap<String, i64>,
    durations_by_type: BTreeMap<String, DurationDto>,
}
#[derive(Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct DurationDto {
    count: i64,
    total_millis: i64,
    min_millis: i64,
    max_millis: i64,
    average_millis: i64,
}
#[derive(Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct BriefingDto {
    date: String,
    overview: String,
    events: Vec<EventDto>,
    open_tasks: Vec<TaskDto>,
    overdue_tasks: Vec<TaskDto>,
    related_documents: Vec<String>,
    feed_items: Vec<ArticleDto>,
    interest_updates: Vec<String>,
    caution_points: Vec<String>,
    next_actions: Vec<String>,
}
#[derive(Deserialize, serde::Serialize)]
struct EventDto {
    id: String,
    summary: String,
    start: Option<String>,
    end: Option<String>,
    location: Option<String>,
    status: Option<String>,
}
#[derive(Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct TaskDto {
    id: i64,
    title: String,
    due_date: Option<String>,
    priority: i32,
    status: String,
}
#[derive(Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct ArticleDto {
    id: i64,
    title: String,
    url: String,
    published_at: Option<String>,
    feed_name: Option<String>,
}
fn text<T: serde::Serialize>(value: T) -> String {
    serde_json::to_string_pretty(&value).unwrap_or_default()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct FeedDto {
    id: i64,
    url: String,
    title: Option<String>,
    display_name: Option<String>,
    enabled: bool,
}
impl FeedDto {
    pub(super) fn item(self) -> WorkspaceItem {
        WorkspaceItem {
            id: Some(self.id.to_string()),
            title: self
                .display_name
                .or(self.title)
                .unwrap_or_else(|| self.url.clone()),
            fields: vec![
                ("URL".into(), self.url),
                ("Enabled".into(), self.enabled.to_string()),
            ],
        }
    }
}
#[derive(Deserialize)]
struct SkillDto {
    name: String,
    description: Option<String>,
    enabled: bool,
    instructions: String,
}
impl SkillDto {
    fn item(self) -> WorkspaceItem {
        WorkspaceItem {
            id: Some(self.name.clone()),
            title: self.name,
            fields: vec![
                ("Description".into(), self.description.unwrap_or_default()),
                ("Enabled".into(), self.enabled.to_string()),
                ("Instructions".into(), self.instructions),
            ],
        }
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SearchDto {
    query: String,
    vector_results: Vec<VectorDto>,
    web_results: Vec<WebDto>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct VectorDto {
    doc_id: String,
    source: String,
    chunk_index: i32,
    score: Option<f64>,
    snippet: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WebDto {
    title: String,
    url: String,
    snippet: String,
    published_at: Option<String>,
    content: Option<String>,
    truncated: bool,
}

pub(super) fn segment(value: &str) -> Result<String> {
    if value.trim().is_empty() || value == "." || value == ".." {
        return Err(AppError::InvalidInput);
    }
    Ok(url::form_urlencoded::byte_serialize(value.as_bytes())
        .collect::<String>()
        .replace('+', "%20"))
}
pub(super) fn numeric(id: i64) -> Result<i64> {
    if id <= 0 {
        Err(AppError::InvalidInput)
    } else {
        Ok(id)
    }
}
pub(super) fn result(title: &str, items: Vec<WorkspaceItem>) -> WorkspaceResult {
    WorkspaceResult {
        title: title.into(),
        items,
    }
}

impl HttpReiClient {
    async fn coaching_operation(&self, op: WorkspaceOperation) -> Result<WorkspaceResult> {
        const MAX_SAFE: i64 = 9_007_199_254_740_991;
        let (request, expected, criteria, enabled) = match op {
            WorkspaceOperation::ActivityCoachingSettings => (
                self.request(Method::GET, "/api/v1/activity/coaching", true)?,
                None,
                None,
                None,
            ),
            WorkspaceOperation::ActivityCoachingConfigure {
                expected_revision,
                mut settings,
            } => {
                if !(0..MAX_SAFE).contains(&expected_revision)
                    || settings.enabled
                    || !settings.valid()
                {
                    return Err(AppError::InvalidInput);
                }
                settings.categories.sort();
                (
                    self.request(Method::POST, "/api/v1/activity/coaching/settings", true)?
                        .json(&json!({"expectedRevision":expected_revision,"settings":settings})),
                    Some(expected_revision + 1),
                    Some(settings),
                    None,
                )
            }
            WorkspaceOperation::ActivityCoachingEnabled {
                expected_revision,
                enabled,
            } => {
                if !(0..MAX_SAFE).contains(&expected_revision) {
                    return Err(AppError::InvalidInput);
                }
                (
                    self.request(Method::POST, "/api/v1/activity/coaching/enabled", true)?
                        .json(&json!({"expectedRevision":expected_revision,"enabled":enabled})),
                    Some(expected_revision + 1),
                    None,
                    Some(enabled),
                )
            }
            _ => return Err(AppError::InvalidInput),
        };
        let mut dto: CoachingSettingsDto = Self::json(request, Operation::Resource).await?;
        dto.settings.categories.sort();
        if dto.schema_version != 1
            || dto.scope != "LOCAL_DEVICE_COACHING_SETTINGS"
            || !(0..=MAX_SAFE).contains(&dto.revision)
            || !dto.settings.valid()
            || expected.is_some_and(|revision| dto.revision != revision)
            || criteria.is_some_and(|settings| settings != dto.settings)
            || enabled.is_some_and(|enabled| dto.settings.enabled != enabled)
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(result(
            "Activity coaching settings",
            vec![WorkspaceItem {
                id: None,
                title: "Coaching settings".into(),
                fields: vec![
                    ("Scope".into(), dto.scope),
                    ("Revision".into(), dto.revision.to_string()),
                    (
                        "Settings".into(),
                        serde_json::to_string(&dto.settings)
                            .map_err(|_| AppError::InvalidResponse)?,
                    ),
                ],
            }],
        ))
    }
    async fn profile_view(&self) -> Result<WorkspaceResult> {
        let dto: ProfileDto = Self::json(
            self.request(Method::GET, "/api/v1/profile", true)?,
            Operation::Resource,
        )
        .await?;
        Ok(result(
            "Profile",
            vec![WorkspaceItem {
                id: None,
                title: "Event statistics".into(),
                fields: vec![
                    ("Total".into(), dto.total.to_string()),
                    ("First".into(), dto.first.unwrap_or_default()),
                    ("Last".into(), dto.last.unwrap_or_default()),
                    ("Counts".into(), text(dto.counts_by_type)),
                    ("Durations (ms)".into(), text(dto.durations_by_type)),
                ],
            }],
        ))
    }
    async fn briefing_view(&self) -> Result<WorkspaceResult> {
        let dto: BriefingDto = Self::json(
            self.request(Method::GET, "/api/v1/briefing", true)?,
            Operation::Resource,
        )
        .await?;
        Ok(result(
            "Briefing",
            vec![WorkspaceItem {
                id: None,
                title: dto.date,
                fields: vec![
                    ("Overview".into(), dto.overview),
                    ("Events".into(), text(dto.events)),
                    ("Open tasks".into(), text(dto.open_tasks)),
                    ("Overdue tasks".into(), text(dto.overdue_tasks)),
                    ("Documents".into(), dto.related_documents.join("\n")),
                    ("Feed items".into(), text(dto.feed_items)),
                    ("Interests".into(), dto.interest_updates.join("\n")),
                    ("Cautions".into(), dto.caution_points.join("\n")),
                    ("Next actions".into(), dto.next_actions.join("\n")),
                ],
            }],
        ))
    }
    pub(super) async fn workspace_operation(
        &self,
        op: WorkspaceOperation,
    ) -> Result<WorkspaceResult> {
        match op {
            op @ (WorkspaceOperation::ActivityCoachingSettings
            | WorkspaceOperation::ActivityCoachingConfigure { .. }
            | WorkspaceOperation::ActivityCoachingEnabled { .. }) => {
                self.coaching_operation(op).await
            }
            WorkspaceOperation::ActivityAnalysis { period, date } => {
                let valid_date = |value: &str| {
                    value.len() == 10
                        && value.bytes().enumerate().all(|(i, c)| {
                            if i == 4 || i == 7 {
                                c == b'-'
                            } else {
                                c.is_ascii_digit()
                            }
                        })
                };
                if !matches!(period.as_str(), "WEEK" | "MONTH")
                    || date.as_deref().is_some_and(|d| !valid_date(d))
                {
                    return Err(AppError::InvalidInput);
                }
                let mut query = vec![("period", period.as_str())];
                if let Some(day) = date.as_deref() {
                    query.push(("date", day));
                }
                let dto: ActivityAnalysisDto = Self::json(
                    self.request(Method::GET, "/api/v1/activity/period", true)?
                        .query(&query),
                    Operation::Resource,
                )
                .await?;
                if dto.schema_version != 1
                    || dto.scope != "LOCAL_DEVICE_OBSERVATIONS"
                    || dto.period != period
                    || !valid_date(&dto.anchor_date)
                    || dto.zone.is_empty()
                    || dto.zone.len() > 128
                    || dto.report.len() > 131072
                {
                    return Err(AppError::InvalidResponse);
                }
                Ok(result(
                    "Activity period analysis",
                    vec![WorkspaceItem {
                        id: None,
                        title: format!("{} {}", dto.period, dto.anchor_date),
                        fields: vec![
                            ("Scope".into(), dto.scope),
                            ("Zone".into(), dto.zone),
                            ("Partial".into(), dto.partial.to_string()),
                            ("Report".into(), dto.report),
                        ],
                    }],
                ))
            }
            op @ (WorkspaceOperation::Schedules { .. }
            | WorkspaceOperation::Schedule { .. }
            | WorkspaceOperation::ScheduleHistory { .. }
            | WorkspaceOperation::ScheduleActivate { .. }
            | WorkspaceOperation::ScheduleCancel { .. }
            | WorkspaceOperation::ScheduleReconcile { .. }) => self.schedule_operation(op).await,
            op @ (WorkspaceOperation::Goals { .. }
            | WorkspaceOperation::Goal { .. }
            | WorkspaceOperation::GoalHistory { .. }
            | WorkspaceOperation::GoalVerify { .. }
            | WorkspaceOperation::GoalRun { .. }
            | WorkspaceOperation::GoalCancel { .. }
            | WorkspaceOperation::GoalReconcile { .. }) => self.goal_operation(op).await,
            op @ (WorkspaceOperation::Dependencies { .. }
            | WorkspaceOperation::DependencyAnswer { .. }) => self.dependency_operation(op).await,
            op @ (WorkspaceOperation::Checkpoints { .. }
            | WorkspaceOperation::Checkpoint { .. }
            | WorkspaceOperation::CheckpointInspect { .. }
            | WorkspaceOperation::CheckpointAbandon { .. }) => self.checkpoint_operation(op).await,
            op @ (WorkspaceOperation::Attention { .. }
            | WorkspaceOperation::AttentionAck { .. }
            | WorkspaceOperation::Approvals { .. }
            | WorkspaceOperation::ApprovalDecision { .. }) => self.attention_operation(op).await,
            WorkspaceOperation::WorkContext { project_id } => {
                let dto: serde_json::Value = Self::json(
                    self.request(
                        Method::GET,
                        &format!(
                            "/api/v1/projects/{}/work-context/summary",
                            segment(&project_id)?
                        ),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                Ok(result(
                    "Work Context",
                    vec![WorkspaceItem {
                        id: Some(project_id),
                        title: "過去の作業状態 / 自動実行しません".into(),
                        fields: vec![
                            (
                                "引き継ぎ".into(),
                                dto.get("text")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or_default()
                                    .into(),
                            ),
                            (
                                "Auto present".into(),
                                dto.get("autoPresent")
                                    .and_then(|v| v.as_bool())
                                    .unwrap_or(false)
                                    .to_string(),
                            ),
                        ],
                    }],
                ))
            }
            WorkspaceOperation::WorkContextHistory { project_id } => {
                let dtos: Vec<serde_json::Value> = Self::json(
                    self.request(
                        Method::GET,
                        &format!(
                            "/api/v1/projects/{}/work-context/history",
                            segment(&project_id)?
                        ),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                Ok(result(
                    "Work Context history",
                    dtos.into_iter()
                        .map(|dto| WorkspaceItem {
                            id: None,
                            title: format!("revision {}", dto["revision"]),
                            fields: vec![
                                (
                                    "Updated".into(),
                                    dto["updatedAt"].as_str().unwrap_or_default().into(),
                                ),
                                ("Items".into(), dto["itemCount"].to_string()),
                            ],
                        })
                        .collect(),
                ))
            }
            WorkspaceOperation::WorkContextUpdate { session_id } => {
                let dto: serde_json::Value = Self::json(
                    self.request(
                        Method::POST,
                        &format!(
                            "/api/v1/sessions/{}/work-context/update",
                            segment(&session_id)?
                        ),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                Ok(result(
                    "Work Context update",
                    vec![WorkspaceItem {
                        id: None,
                        title: "確定Turnの保存結果".into(),
                        fields: vec![("Result".into(), dto.to_string())],
                    }],
                ))
            }
            WorkspaceOperation::Feeds => {
                let dtos: Vec<FeedDto> = Self::json(
                    self.request(Method::GET, "/api/v1/feed", true)?,
                    Operation::Resource,
                )
                .await?;
                Ok(result(
                    "Feed",
                    dtos.into_iter().map(FeedDto::item).collect(),
                ))
            }
            WorkspaceOperation::Feed { id } => {
                let dto: FeedDto = Self::json(
                    self.request(Method::GET, &format!("/api/v1/feed/{}", numeric(id)?), true)?,
                    Operation::Resource,
                )
                .await?;
                Ok(result("Feed", vec![dto.item()]))
            }
            WorkspaceOperation::Skills => {
                let dtos: Vec<SkillDto> = Self::json(
                    self.request(Method::GET, "/api/v1/skills", true)?,
                    Operation::Resource,
                )
                .await?;
                Ok(result(
                    "Skills",
                    dtos.into_iter().map(SkillDto::item).collect(),
                ))
            }
            WorkspaceOperation::Skill { name } => {
                let dto: SkillDto = Self::json(
                    self.request(
                        Method::GET,
                        &format!("/api/v1/skills/{}", segment(&name)?),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                Ok(result("Skill", vec![dto.item()]))
            }
            WorkspaceOperation::Search {
                query,
                vector_top_k,
                web_top_k,
                threshold,
            } => {
                if query.trim().is_empty()
                    || query.len() > 2000
                    || !(1..=100).contains(&vector_top_k)
                    || !(1..=100).contains(&web_top_k)
                    || !threshold.is_finite()
                    || !(0.0..=1.0).contains(&threshold)
                {
                    return Err(AppError::InvalidInput);
                }
                let dto:SearchDto=Self::json(self.request(Method::POST,"/api/v1/search",true)?.json(&json!({"query":query,"vectorTopK":vector_top_k,"webTopK":web_top_k,"threshold":threshold})),Operation::Resource).await?;
                let mut items: Vec<_> = dto
                    .vector_results
                    .into_iter()
                    .map(|v| WorkspaceItem {
                        id: Some(v.doc_id),
                        title: v.source,
                        fields: vec![
                            ("Chunk".into(), v.chunk_index.to_string()),
                            (
                                "Score".into(),
                                v.score.map(|s| s.to_string()).unwrap_or_default(),
                            ),
                            ("Snippet".into(), v.snippet),
                        ],
                    })
                    .collect();
                items.extend(dto.web_results.into_iter().map(|v| WorkspaceItem {
                    id: None,
                    title: v.title,
                    fields: vec![
                        ("URL".into(), v.url),
                        ("Snippet".into(), v.snippet),
                        ("Published".into(), v.published_at.unwrap_or_default()),
                        ("Content".into(), v.content.unwrap_or_default()),
                        ("Truncated".into(), v.truncated.to_string()),
                    ],
                }));
                Ok(result(&dto.query, items))
            }
            WorkspaceOperation::Profile => self.profile_view().await,
            WorkspaceOperation::Briefing => self.briefing_view().await,
            op => self.stateful_operation(op).await,
        }
    }
}
