use super::workspace::{result, segment};
use super::*;
use serde::Deserialize;
use serde_json::json;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DraftDto {
    id: String,
    action_type: String,
    trigger: String,
    created_at: String,
    updated_at: String,
    expires_at: String,
    target: TargetDto,
    content: ContentDto,
    state: String,
    rejection_reason: Option<String>,
    rejection_reason_public: bool,
    regeneration_attempts: u32,
    last_regeneration_attempt_at: Option<String>,
    publication: Option<PublicationDto>,
}
#[derive(Deserialize)]
struct TargetDto {
    did: String,
    handle: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ContentDto {
    revision: i32,
    text: String,
    reason: String,
    source_ids: Vec<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PublicationDto {
    uri: Option<String>,
    cid: Option<String>,
    result: Option<String>,
    failure_reason: Option<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SourceDto {
    id: String,
    revision: i32,
    text: String,
    public_use_allowed: bool,
    updated_at: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GenerationDto {
    status: String,
    reason: String,
    draft_id: Option<String>,
    draft: Option<DraftDto>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct NoticeDto {
    id: String,
    draft_id: String,
    status: String,
    created_at: String,
}

impl NoticeDto {
    fn item(self, expected: Option<&str>) -> Result<WorkspaceItem> {
        let valid_draft =
            !self.draft_id.is_empty()
                && self.draft_id.len() <= 256
                && self.draft_id.bytes().enumerate().all(|(i, b)| {
                    b.is_ascii_alphanumeric() || (i > 0 && [b'-', b'_'].contains(&b))
                });
        if uuid::Uuid::parse_str(&self.id).is_err()
            || !valid_draft
            || !valid_time(&self.created_at)
            || expected.is_some_and(|id| id != self.id)
            || if expected.is_some() {
                !["ACKNOWLEDGED", "RESOLVED"].contains(&self.status.as_str())
            } else {
                !["OPEN", "HELD"].contains(&self.status.as_str())
            }
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(WorkspaceItem {
            id: Some(self.id),
            title: "投稿案の確認待ち".into(),
            fields: vec![
                ("Type".into(), "AutonomousNotice".into()),
                ("DraftId".into(), self.draft_id),
                ("Status".into(), self.status),
                ("CreatedAt".into(), self.created_at),
            ],
        })
    }
}

fn valid_time(value: &str) -> bool {
    chrono::DateTime::parse_from_rfc3339(value).is_ok()
}
fn nonblank(value: &str, max: usize) -> bool {
    !value.trim().is_empty() && value.len() <= max
}
fn require_input(valid: bool) -> Result<()> {
    if valid {
        Ok(())
    } else {
        Err(AppError::InvalidInput)
    }
}
fn checked_id(id: &str) -> Result<String> {
    require_input(id.len() <= 256)?;
    segment(id)
}
fn resource_path(sources: bool, id: &str, suffix: &str) -> Result<String> {
    Ok(format!(
        "/api/v1/autonomous/{}/{id}{suffix}",
        if sources { "sources" } else { "drafts" },
        id = checked_id(id)?
    ))
}
impl DraftDto {
    fn item(self, expected: Option<&str>) -> Result<WorkspaceItem> {
        if !nonblank(&self.id, 256)
            || expected.is_some_and(|id| id != self.id)
            || self.action_type != "BLUESKY_POST"
            || self.content.revision < 1
            || !nonblank(&self.content.text, 3000)
            || self.content.source_ids.len() > 64
            || self.content.source_ids.iter().any(|id| !nonblank(id, 128))
            || !self.target.did.starts_with("did:")
            || !nonblank(&self.target.handle, 253)
            || ![
                "PENDING_REVIEW",
                "REJECTED",
                "EXPIRED",
                "PUBLISHING",
                "PUBLISHED",
                "FAILED",
                "UNKNOWN",
            ]
            .contains(&self.state.as_str())
            || !["MANUAL", "SCHEDULED"].contains(&self.trigger.as_str())
            || ![&self.created_at, &self.updated_at, &self.expires_at]
                .into_iter()
                .all(|s| valid_time(s))
            || self
                .last_regeneration_attempt_at
                .as_deref()
                .is_some_and(|s| !valid_time(s))
        {
            return Err(AppError::InvalidResponse);
        }
        let publication = self.publication;
        let mut fields: Vec<(String, String)> = vec![
            ("Type".into(), "AutonomousDraft".into()),
            ("Text".into(), self.content.text),
            ("Revision".into(), self.content.revision.to_string()),
            ("Status".into(), self.state),
            ("Account".into(), self.target.handle),
            ("DID".into(), self.target.did),
            ("Reason".into(), self.content.reason),
            ("SourceIds".into(), self.content.source_ids.join("\n")),
            ("CreatedAt".into(), self.created_at),
            ("UpdatedAt".into(), self.updated_at),
            ("ExpiresAt".into(), self.expires_at),
            ("Trigger".into(), self.trigger),
            (
                "RegenerationAttempts".into(),
                self.regeneration_attempts.to_string(),
            ),
            (
                "LastRegenerationAttemptAt".into(),
                self.last_regeneration_attempt_at.unwrap_or_default(),
            ),
            (
                "RejectionReason".into(),
                self.rejection_reason.unwrap_or_default(),
            ),
            (
                "RejectionReasonPublic".into(),
                self.rejection_reason_public.to_string(),
            ),
        ];
        if let Some(p) = publication {
            fields.extend([
                ("Result".into(), p.result.unwrap_or_default()),
                ("Failure".into(), p.failure_reason.unwrap_or_default()),
                ("URI".into(), p.uri.unwrap_or_default()),
                ("CID".into(), p.cid.unwrap_or_default()),
            ]);
        }
        Ok(WorkspaceItem {
            id: Some(self.id),
            title: "投稿案".into(),
            fields,
        })
    }
}
impl SourceDto {
    fn item(self, expected: Option<&str>) -> Result<WorkspaceItem> {
        if !nonblank(&self.id, 256)
            || expected.is_some_and(|id| id != self.id)
            || self.revision < 1
            || !nonblank(&self.text, 8192)
            || !valid_time(&self.updated_at)
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(WorkspaceItem {
            id: Some(self.id),
            title: "公開用の話題".into(),
            fields: vec![
                ("Type".into(), "AutonomousSource".into()),
                ("Text".into(), self.text),
                ("Revision".into(), self.revision.to_string()),
                (
                    "PublicUseAllowed".into(),
                    self.public_use_allowed.to_string(),
                ),
                ("UpdatedAt".into(), self.updated_at),
            ],
        })
    }
}
fn page(items: Vec<WorkspaceItem>, limit: i32) -> Result<WorkspaceResult> {
    let unique = items
        .iter()
        .map(|item| item.id.as_deref())
        .collect::<std::collections::HashSet<_>>();
    if items.len() > limit as usize || unique.len() != items.len() {
        return Err(AppError::InvalidResponse);
    }
    Ok(result("自発投稿案", items))
}

impl HttpReiClient {
    pub(super) async fn autonomous_notifications(
        &self,
        op: WorkspaceOperation,
    ) -> Result<WorkspaceResult> {
        match op {
            WorkspaceOperation::AutonomousNotifications { limit } => {
                require_input((1..=200).contains(&limit))?;
                let path = format!("/api/v1/autonomous/notifications?limit={limit}");
                let request = self.request(Method::GET, &path, true)?;
                let items = Self::json::<Vec<NoticeDto>>(request, Operation::Resource)
                    .await?
                    .into_iter()
                    .map(|dto| dto.item(None))
                    .collect::<Result<Vec<_>>>()?;
                page(items, limit)
            }
            WorkspaceOperation::AutonomousNoticeAck { id } => {
                require_input(id.len() == 36 && uuid::Uuid::parse_str(&id).is_ok())?;
                let path = format!("/api/v1/autonomous/notifications/{}/ack", checked_id(&id)?);
                let request = self.request(Method::POST, &path, true)?.json(&json!({}));
                let item = Self::json::<NoticeDto>(request, Operation::Resource)
                    .await?
                    .item(Some(&id))?;
                Ok(result("通知を既読にしました", vec![item]))
            }
            _ => Err(AppError::InvalidInput),
        }
    }

    pub(super) async fn autonomous_operation(
        &self,
        op: WorkspaceOperation,
    ) -> Result<WorkspaceResult> {
        use WorkspaceOperation::*;
        let source_operation = matches!(
            &op,
            AutonomousSources { .. } | AutonomousSource { .. } | AutonomousEditSource { .. }
        );
        let permit_operation = matches!(&op, AutonomousPermitSource { .. });
        let publishing = matches!(&op, AutonomousPublish { .. });
        let reconciling = matches!(&op, AutonomousReconcile { .. });
        let revised = match &op {
            AutonomousEdit {
                expected_revision, ..
            }
            | AutonomousEditSource {
                expected_revision, ..
            }
            | AutonomousPermitSource {
                expected_revision, ..
            }
            | AutonomousRevokeSource {
                expected_revision, ..
            }
            | AutonomousRegenerate {
                expected_revision, ..
            } => Some(
                expected_revision
                    .checked_add(1)
                    .ok_or(AppError::InvalidInput)?,
            ),
            AutonomousReject {
                expected_revision, ..
            }
            | AutonomousPublish {
                expected_revision, ..
            }
            | AutonomousReconcile {
                expected_revision, ..
            } => Some(*expected_revision),
            _ => None,
        };
        let rejection = matches!(&op, AutonomousReject { .. });
        let expected_permission = match &op {
            AutonomousEditSource { .. } | AutonomousRevokeSource { .. } => Some(false),
            AutonomousPermitSource { .. } => Some(true),
            AutonomousAddSource {
                public_use_allowed, ..
            } => Some(*public_use_allowed),
            _ => None,
        };
        let (path, body, expected, sources, generation, limit) = match op {
            AutonomousDrafts { limit } | AutonomousSources { limit } => {
                require_input((1..=200).contains(&limit))?;
                let sources = source_operation;
                (
                    format!(
                        "/api/v1/autonomous/{}?limit={limit}",
                        if sources { "sources" } else { "drafts" }
                    ),
                    None,
                    None,
                    sources,
                    false,
                    Some(limit),
                )
            }
            AutonomousDraft { id } | AutonomousSource { id } => {
                let sources = source_operation;
                (
                    resource_path(sources, &id, "")?,
                    None,
                    Some(id),
                    sources,
                    false,
                    None,
                )
            }
            AutonomousGenerate => (
                "/api/v1/autonomous/drafts/generate".into(),
                Some(json!({})),
                None,
                false,
                true,
                None,
            ),
            AutonomousEdit {
                id,
                expected_revision,
                text,
            }
            | AutonomousEditSource {
                id,
                expected_revision,
                text,
            } => {
                let sources = source_operation;
                require_input(
                    expected_revision > 0 && nonblank(&text, if sources { 8192 } else { 3000 }),
                )?;
                (
                    resource_path(sources, &id, "/edit")?,
                    Some(json!({"expectedRevision":expected_revision,"text":text})),
                    Some(id),
                    sources,
                    false,
                    None,
                )
            }
            AutonomousReject {
                id,
                expected_revision,
                reason,
            } => {
                require_input(expected_revision > 0 && reason.encode_utf16().count() <= 2048)?;
                (
                    resource_path(false, &id, "/reject")?,
                    Some(json!({"expectedRevision":expected_revision,"reason":reason})),
                    Some(id),
                    false,
                    false,
                    None,
                )
            }
            AutonomousRegenerate {
                id,
                expected_revision,
            } => {
                require_input(expected_revision > 0)?;
                (
                    resource_path(false, &id, "/regenerate")?,
                    Some(json!({"expectedRevision":expected_revision})),
                    Some(id),
                    false,
                    true,
                    None,
                )
            }
            AutonomousPublish {
                id,
                expected_revision,
            }
            | AutonomousReconcile {
                id,
                expected_revision,
            } => {
                require_input(expected_revision > 0)?;
                (
                    resource_path(
                        false,
                        &id,
                        if publishing { "/publish" } else { "/reconcile" },
                    )?,
                    Some(json!({"expectedRevision":expected_revision})),
                    Some(id),
                    false,
                    false,
                    None,
                )
            }
            AutonomousAddSource {
                text,
                public_use_allowed,
            } => {
                require_input(nonblank(&text, 8192))?;
                (
                    "/api/v1/autonomous/sources".into(),
                    Some(json!({"text":text,"publicUseAllowed":public_use_allowed})),
                    None,
                    true,
                    false,
                    None,
                )
            }
            AutonomousPermitSource {
                id,
                expected_revision,
            }
            | AutonomousRevokeSource {
                id,
                expected_revision,
            } => {
                let permit = permit_operation;
                require_input(expected_revision > 0)?;
                (
                    resource_path(true, &id, if permit { "/permit" } else { "/revoke" })?,
                    Some(json!({"expectedRevision":expected_revision})),
                    Some(id),
                    true,
                    false,
                    None,
                )
            }
            _ => return Err(AppError::InvalidInput),
        };
        let request = self.request(
            if body.is_some() {
                Method::POST
            } else {
                Method::GET
            },
            &path,
            true,
        )?;
        let request = if let Some(body) = body {
            request.json(&body)
        } else {
            request
        };
        if generation {
            let response: GenerationDto =
                Self::json_with_timeout(request, Operation::Resource, Duration::from_secs(180))
                    .await?;
            if !["DRAFT_CREATED", "DRAFT_REGENERATED", "SKIPPED", "FAILED"]
                .contains(&response.status.as_str())
                || response.reason.len() > 8192
                || expected
                    .as_ref()
                    .is_some_and(|id| response.draft_id.as_ref() != Some(id))
                || response.draft.as_ref().map(|d| &d.id) != response.draft_id.as_ref()
                || (response.status.starts_with("DRAFT_")
                    && (response.status
                        != if expected.is_some() {
                            "DRAFT_REGENERATED"
                        } else {
                            "DRAFT_CREATED"
                        }
                        || response.draft.as_ref().is_none_or(|d| {
                            d.state != "PENDING_REVIEW"
                                || d.content.revision != revised.unwrap_or(1)
                        })))
            {
                return Err(AppError::InvalidResponse);
            }
            let mut items = vec![WorkspaceItem {
                id: None,
                title: "生成結果".into(),
                fields: vec![
                    ("Type".into(), "AutonomousGeneration".into()),
                    ("GenerationStatus".into(), response.status),
                    ("GenerationReason".into(), response.reason),
                ],
            }];
            if let Some(draft) = response.draft {
                items.push(draft.item(expected.as_deref())?);
            }
            return Ok(result("投稿案の生成結果", items));
        }
        if let Some(limit) = limit {
            let items = if sources {
                Self::json::<Vec<SourceDto>>(request, Operation::Resource)
                    .await?
                    .into_iter()
                    .map(|dto| dto.item(None))
                    .collect::<Result<Vec<_>>>()?
            } else {
                Self::json::<Vec<DraftDto>>(request, Operation::Resource)
                    .await?
                    .into_iter()
                    .map(|dto| dto.item(None))
                    .collect::<Result<Vec<_>>>()?
            };
            return page(items, limit);
        }
        let item = if sources {
            let dto = Self::json::<SourceDto>(request, Operation::Resource).await?;
            if revised.is_some_and(|revision| dto.revision != revision)
                || expected_permission
                    .is_some_and(|permission| dto.public_use_allowed != permission)
            {
                return Err(AppError::InvalidResponse);
            }
            dto.item(expected.as_deref())?
        } else {
            let dto = if publishing || reconciling {
                Self::json_with_timeout::<DraftDto>(
                    request,
                    Operation::Resource,
                    Duration::from_secs(if publishing { 180 } else { 90 }),
                )
                .await?
            } else {
                Self::json::<DraftDto>(request, Operation::Resource).await?
            };
            let invalid_receipt = (publishing || reconciling)
                && dto.publication.as_ref().is_none_or(|publication| {
                    publication.result.as_deref() != Some(dto.state.as_str())
                        || (dto.state == "PUBLISHED"
                            && (publication
                                .uri
                                .as_deref()
                                .is_none_or(|uri| !nonblank(uri, 2048))
                                || publication
                                    .cid
                                    .as_deref()
                                    .is_none_or(|cid| !nonblank(cid, 256))))
                });
            if invalid_receipt
                || revised.is_some_and(|revision| {
                    dto.content.revision != revision
                        || if publishing {
                            !["PUBLISHED", "FAILED", "UNKNOWN"].contains(&dto.state.as_str())
                        } else if reconciling {
                            !["PUBLISHED", "UNKNOWN"].contains(&dto.state.as_str())
                        } else {
                            dto.state
                                != if rejection {
                                    "REJECTED"
                                } else {
                                    "PENDING_REVIEW"
                                }
                        }
                })
            {
                return Err(AppError::InvalidResponse);
            }
            dto.item(expected.as_deref())?
        };
        Ok(result("自発投稿案", vec![item]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{body::Body, response::Response, Router};
    use futures_util::stream;
    #[tokio::test]
    async fn autonomous_deadline_covers_a_started_response_body() {
        let app = Router::new().fallback(|| async {
            let chunks = stream::unfold(0, |index| async move {
                if index == 0 {
                    Some((Ok::<_, std::io::Error>("{"), 1))
                } else {
                    tokio::time::sleep(Duration::from_secs(5)).await;
                    None
                }
            });
            Response::builder()
                .header("content-type", "application/json")
                .body(Body::from_stream(chunks))
                .unwrap()
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let api = HttpReiClient::new(&url, None).unwrap();
        let result = tokio::time::timeout(
            Duration::from_secs(2),
            HttpReiClient::json_with_timeout::<serde_json::Value>(
                api.request(Method::GET, "/", false).unwrap(),
                Operation::Resource,
                Duration::from_millis(500),
            ),
        )
        .await;
        task.abort();
        assert!(
            matches!(result, Ok(Err(AppError::RequestTimeout))),
            "{result:?}"
        );
    }
}
