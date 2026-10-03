use super::workspace::{numeric, result, segment, FeedDto};
use super::*;
use serde::Deserialize;
use serde_json::{json, Value};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReminderDto {
    id: i64,
    message: String,
    #[serde(rename = "type")]
    kind: String,
    remind_at: String,
    target_at: Option<String>,
    minutes_before: Option<i32>,
    notified: bool,
}
impl ReminderDto {
    fn item(self) -> WorkspaceItem {
        WorkspaceItem {
            id: Some(self.id.to_string()),
            title: self.message,
            fields: vec![
                ("Type".into(), self.kind),
                ("Remind at".into(), self.remind_at),
                ("Target".into(), self.target_at.unwrap_or_default()),
                (
                    "Minutes before".into(),
                    self.minutes_before
                        .map(|v| v.to_string())
                        .unwrap_or_default(),
                ),
                ("Notified".into(), self.notified.to_string()),
            ],
        }
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct InterestDto {
    id: i64,
    topic: String,
    reason: String,
    search_query: String,
    summary: String,
    source_urls: Vec<String>,
    created_at: String,
}
impl InterestDto {
    fn item(self) -> WorkspaceItem {
        WorkspaceItem {
            id: Some(self.id.to_string()),
            title: self.topic,
            fields: vec![
                ("Reason".into(), self.reason),
                ("Query".into(), self.search_query),
                ("Summary".into(), self.summary),
                ("Sources".into(), self.source_urls.join("\n")),
                ("Created".into(), self.created_at),
            ],
        }
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MemoryDto {
    id: String,
    content: String,
    #[serde(rename = "type")]
    kind: String,
    scope: String,
    status: String,
    confidence: f64,
    expires_at: Option<String>,
    created_at: String,
    updated_at: String,
}
impl MemoryDto {
    fn item(self) -> WorkspaceItem {
        WorkspaceItem {
            id: Some(self.id),
            title: self.content,
            fields: vec![
                ("Type".into(), self.kind),
                ("Scope".into(), self.scope),
                ("Status".into(), self.status),
                ("Confidence".into(), self.confidence.to_string()),
                ("Expires".into(), self.expires_at.unwrap_or_default()),
                ("Created".into(), self.created_at),
                ("Updated".into(), self.updated_at),
            ],
        }
    }
}
#[derive(Deserialize)]
struct ReloadDto {
    count: usize,
}
fn text(value: &str, max: usize) -> Result<()> {
    if value.trim().is_empty() || value.chars().count() > max {
        Err(AppError::InvalidInput)
    } else {
        Ok(())
    }
}
fn http_url(value: &str) -> Result<()> {
    text(value, 4096)?;
    let url = url::Url::parse(value).map_err(|_| AppError::InvalidInput)?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(AppError::InvalidInput);
    }
    Ok(())
}
impl HttpReiClient {
    async fn resource<T: DeserializeOwned>(
        &self,
        method: Method,
        path: &str,
        body: Option<Value>,
    ) -> Result<T> {
        let expected = if method == Method::POST && path != "/api/v1/skills/reload" {
            reqwest::StatusCode::CREATED
        } else {
            reqwest::StatusCode::OK
        };
        let mut request = self.request(method, path, true)?;
        if let Some(body) = body {
            request = request.json(&body);
        }
        let response = Self::checked(
            request.timeout(Duration::from_secs(30)),
            Operation::Resource,
        )
        .await?;
        if response.status() != expected
            || !response
                .headers()
                .get("content-type")
                .and_then(|h| h.to_str().ok())
                .unwrap_or("")
                .starts_with("application/json")
        {
            return Err(AppError::InvalidResponse);
        }
        response.json().await.map_err(|e| {
            if e.is_timeout() {
                AppError::RequestTimeout
            } else {
                AppError::InvalidResponse
            }
        })
    }
    async fn remove_resource(&self, path: &str, title: &str) -> Result<WorkspaceResult> {
        let response = Self::checked(
            self.request(Method::DELETE, path, true)?
                .timeout(Duration::from_secs(30)),
            Operation::Resource,
        )
        .await?;
        if response.status() != reqwest::StatusCode::NO_CONTENT {
            return Err(AppError::InvalidResponse);
        }
        Ok(result(title, vec![]))
    }
    pub(super) async fn stateful_operation(
        &self,
        op: WorkspaceOperation,
    ) -> Result<WorkspaceResult> {
        use WorkspaceOperation::*;
        match op {
            CreateFeed { url, display_name } => {
                http_url(&url)?;
                if let Some(name) = &display_name {
                    text(name, 200)?;
                }
                let dto: FeedDto = self
                    .resource(
                        Method::POST,
                        "/api/v1/feed",
                        Some(json!({"url":url,"displayName":display_name})),
                    )
                    .await?;
                Ok(result("Feed", vec![dto.item()]))
            }
            UpdateFeed {
                id,
                display_name,
                enabled,
            } => {
                if display_name.is_none() && enabled.is_none() {
                    return Err(AppError::InvalidInput);
                }
                let mut body = json!({});
                if let Some(name) = display_name {
                    text(&name, 200)?;
                    body["displayName"] = name.into();
                }
                if let Some(enabled) = enabled {
                    body["enabled"] = enabled.into();
                }
                let dto: FeedDto = self
                    .resource(
                        Method::PATCH,
                        &format!("/api/v1/feed/{}", numeric(id)?),
                        Some(body),
                    )
                    .await?;
                Ok(result("Feed", vec![dto.item()]))
            }
            DeleteFeed { id } => {
                self.remove_resource(&format!("/api/v1/feed/{}", numeric(id)?), "Feed deleted")
                    .await
            }
            Reminders => {
                let dtos: Vec<ReminderDto> = self
                    .resource(Method::GET, "/api/v1/reminders", None)
                    .await?;
                Ok(result(
                    "Reminders",
                    dtos.into_iter().map(ReminderDto::item).collect(),
                ))
            }
            Reminder { id } => {
                let dto: ReminderDto = self
                    .resource(
                        Method::GET,
                        &format!("/api/v1/reminders/{}", numeric(id)?),
                        None,
                    )
                    .await?;
                Ok(result("Reminder", vec![dto.item()]))
            }
            CreateReminder {
                message,
                at,
                target,
                minutes_before,
            } => {
                text(&message, 2000)?;
                if !((at.is_some() && target.is_none() && minutes_before.is_none())
                    || (at.is_none()
                        && target.is_some()
                        && minutes_before.is_some_and(|m| (0..=525600).contains(&m))))
                {
                    return Err(AppError::InvalidInput);
                }
                let dto:ReminderDto=self.resource(Method::POST,"/api/v1/reminders",Some(json!({"message":message,"at":at,"target":target,"minutesBefore":minutes_before}))).await?;
                Ok(result("Reminder", vec![dto.item()]))
            }
            DeleteReminder { id } => {
                self.remove_resource(
                    &format!("/api/v1/reminders/{}", numeric(id)?),
                    "Reminder deleted",
                )
                .await
            }
            Interests { hours } => {
                if !(1..=8760).contains(&hours) {
                    return Err(AppError::InvalidInput);
                }
                let dtos: Vec<InterestDto> = self
                    .resource(
                        Method::GET,
                        &format!("/api/v1/interests?hours={hours}"),
                        None,
                    )
                    .await?;
                Ok(result(
                    "Interests",
                    dtos.into_iter().map(InterestDto::item).collect(),
                ))
            }
            CreateInterest {
                topic,
                reason,
                search_query,
                summary,
                source_urls,
            } => {
                text(&topic, 200)?;
                text(&reason, 2000)?;
                text(&search_query, 2000)?;
                text(&summary, 10000)?;
                if source_urls.len() > 20 {
                    return Err(AppError::InvalidInput);
                }
                for url in &source_urls {
                    http_url(url)?;
                }
                let dto:InterestDto=self.resource(Method::POST,"/api/v1/interests",Some(json!({"topic":topic,"reason":reason,"searchQuery":search_query,"summary":summary,"sourceUrls":source_urls}))).await?;
                Ok(result("Interest", vec![dto.item()]))
            }
            Memories => {
                let dtos: Vec<MemoryDto> =
                    self.resource(Method::GET, "/api/v1/memories", None).await?;
                Ok(result(
                    "Memories",
                    dtos.into_iter().map(MemoryDto::item).collect(),
                ))
            }
            Memory { id } => {
                let dto: MemoryDto = self
                    .resource(
                        Method::GET,
                        &format!("/api/v1/memories/{}", segment(&id)?),
                        None,
                    )
                    .await?;
                Ok(result("Memory", vec![dto.item()]))
            }
            CreateMemory {
                content,
                memory_type,
                scope,
                confidence,
            } => {
                text(&content, 20000)?;
                text(&memory_type, 64)?;
                if !matches!(
                    scope.as_str(),
                    "GLOBAL" | "SHORT_TERM" | "LONG_TERM" | "PERMANENT"
                ) || !confidence.is_finite()
                    || !(0.0..=1.0).contains(&confidence)
                {
                    return Err(AppError::InvalidInput);
                }
                let dto:MemoryDto=self.resource(Method::POST,"/api/v1/memories",Some(json!({"content":content,"type":memory_type,"scope":scope,"confidence":confidence}))).await?;
                Ok(result("Memory", vec![dto.item()]))
            }
            DeleteMemory { id } => {
                self.remove_resource(
                    &format!("/api/v1/memories/{}", segment(&id)?),
                    "Memory deleted",
                )
                .await
            }
            ReloadSkills => {
                let dto: ReloadDto = self
                    .resource(Method::POST, "/api/v1/skills/reload", Some(json!({})))
                    .await?;
                Ok(result(
                    "Skills reloaded",
                    vec![WorkspaceItem {
                        id: None,
                        title: "Reloaded".into(),
                        fields: vec![("Count".into(), dto.count.to_string())],
                    }],
                ))
            }
            _ => Err(AppError::InvalidInput),
        }
    }
}
