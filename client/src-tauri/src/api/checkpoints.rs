use super::workspace::{result, segment};
use super::*;
use serde::Deserialize;
use serde_json::Value;
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Checkpoint {
    schema_version: u32,
    revision: u64,
    task_id: String,
    project_id: String,
    session_id: String,
    run_id: String,
    request: String,
    status: String,
    next_action: Option<String>,
    blockers: Vec<String>,
    operations: Vec<Value>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Reconciliation {
    decision: String,
    usable: Vec<String>,
    changed: Vec<String>,
    recheck: Vec<String>,
    unknown_operations: Vec<Value>,
    blockers: Vec<String>,
    next_action: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Resume {
    task_id: String,
    run_id: String,
    previous_run_id: String,
    checkpoint_revision: u64,
    reconciliation: Reconciliation,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ManagedRun {
    project_id: Option<String>,
    project_name: String,
    run_id: String,
    request: String,
    status: String,
    started_at: Option<String>,
    failure: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RunCheckpoint {
    task_id: String,
    run_id: String,
    original_run_id: String,
    saved_at: String,
    status: String,
    revision: u64,
    request: String,
    discardable: bool,
}

impl Checkpoint {
    fn validate(&self, project: &str, task: Option<&str>) -> Result<()> {
        if self.schema_version != 1
            || self.project_id != project
            || self.task_id.is_empty()
            || self.session_id.is_empty()
            || self.run_id.is_empty()
            || task.is_some_and(|id| id != self.task_id)
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(())
    }
    fn item(self) -> WorkspaceItem {
        WorkspaceItem {
            id: Some(self.task_id),
            title: self.request,
            fields: vec![
                ("Project".into(), self.project_id),
                ("Session".into(), self.session_id),
                ("Run".into(), self.run_id),
                ("Revision".into(), self.revision.to_string()),
                ("Status".into(), self.status),
                ("Next".into(), self.next_action.unwrap_or_default()),
                ("Blockers".into(), self.blockers.join("\n")),
                (
                    "Operations".into(),
                    serde_json::to_string_pretty(&self.operations).unwrap_or_default(),
                ),
            ],
        }
    }
}
impl Reconciliation {
    fn item(self, project: &str, task: &str) -> Result<WorkspaceItem> {
        if !["CONTINUE", "CONFIRMATION_REQUIRED", "BLOCKED"].contains(&self.decision.as_str()) {
            return Err(AppError::InvalidResponse);
        }
        Ok(WorkspaceItem {
            id: Some(task.into()),
            title: "Checkpoint reconciliation".into(),
            fields: vec![
                ("Project".into(), project.into()),
                ("Decision".into(), self.decision),
                ("Usable".into(), self.usable.join("\n")),
                ("Changed".into(), self.changed.join("\n")),
                ("Recheck".into(), self.recheck.join("\n")),
                (
                    "Unknown operations".into(),
                    serde_json::to_string_pretty(&self.unknown_operations).unwrap_or_default(),
                ),
                ("Blockers".into(), self.blockers.join("\n")),
                ("Next".into(), self.next_action),
            ],
        })
    }
}
impl HttpReiClient {
    async fn saved_checkpoint(&self, project: &str, task: &str) -> Result<Checkpoint> {
        let row: Checkpoint = Self::json(
            self.request(
                Method::GET,
                &format!(
                    "/api/v1/projects/{}/checkpoints/{}",
                    segment(project)?,
                    segment(task)?
                ),
                true,
            )?,
            Operation::Resource,
        )
        .await?;
        row.validate(project, Some(task))?;
        Ok(row)
    }
    pub(super) async fn saved_checkpoint_run(
        &self,
        project: &str,
        task: &str,
        resume: bool,
    ) -> Result<CheckpointReceipt> {
        let saved = self.saved_checkpoint(project, task).await?;
        let (run, revision) = if resume {
            let response = Self::checked(
                self.request(
                    Method::POST,
                    &format!(
                        "/api/v1/projects/{}/checkpoints/{}/resume",
                        segment(project)?,
                        segment(task)?
                    ),
                    true,
                )?
                .timeout(Duration::from_secs(30)),
                Operation::Resource,
            )
            .await?;
            if response.status() != reqwest::StatusCode::ACCEPTED {
                return Err(AppError::InvalidResponse);
            }
            let receipt: Resume = Self::decode_json(response, RUN_RECEIPT_JSON_LIMIT).await?;
            if receipt.task_id != task
                || receipt.run_id.is_empty()
                || receipt.previous_run_id.is_empty()
                || !["CONTINUE", "CONFIRMATION_REQUIRED"]
                    .contains(&receipt.reconciliation.decision.as_str())
            {
                return Err(AppError::InvalidResponse);
            }
            (receipt.run_id, receipt.checkpoint_revision)
        } else {
            (saved.run_id, saved.revision)
        };
        let snapshot = self.run(&run).await?;
        if snapshot.run_id != run
            || snapshot.project_id != project
            || snapshot.session_id != saved.session_id
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(CheckpointReceipt {
            task_id: task.into(),
            checkpoint_revision: revision,
            snapshot,
        })
    }
    pub(super) async fn checkpoint_operation(
        &self,
        op: WorkspaceOperation,
    ) -> Result<WorkspaceResult> {
        match op {
            WorkspaceOperation::ManagedRuns => {
                let rows: Vec<ManagedRun> = Self::json(
                    self.request(Method::GET, "/api/v1/run-management", true)?,
                    Operation::Resource,
                )
                .await?;
                let items = rows
                    .into_iter()
                    .map(|row| WorkspaceItem {
                        id: Some(row.run_id),
                        title: row.request,
                        fields: vec![
                            ("Project".into(), row.project_id.unwrap_or_default()),
                            ("Project name".into(), row.project_name),
                            ("Status".into(), row.status),
                            ("Started".into(), row.started_at.unwrap_or_default()),
                            ("Failure".into(), row.failure),
                        ],
                    })
                    .collect();
                Ok(result("Runs", items))
            }
            WorkspaceOperation::RunCheckpoints { project_id, run_id } => {
                let rows: Vec<RunCheckpoint> = Self::json(
                    self.request(
                        Method::GET,
                        &format!(
                            "/api/v1/run-management/{}/{}/checkpoints",
                            segment(&project_id)?,
                            segment(&run_id)?
                        ),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                let items = rows
                    .into_iter()
                    .map(|row| WorkspaceItem {
                        id: Some(row.task_id),
                        title: row.request,
                        fields: vec![
                            ("Run".into(), row.run_id),
                            ("Original run".into(), row.original_run_id),
                            ("Saved".into(), row.saved_at),
                            ("Status".into(), row.status),
                            ("Revision".into(), row.revision.to_string()),
                            ("Discardable".into(), row.discardable.to_string()),
                        ],
                    })
                    .collect();
                Ok(result("Run checkpoints", items))
            }
            WorkspaceOperation::DiscardRunCheckpoint {
                project_id,
                run_id,
                task_id,
                revision,
            } => {
                let revision: u64 = revision.parse().map_err(|_| AppError::InvalidInput)?;
                let response = Self::checked(
                    self.request(
                        Method::POST,
                        &format!(
                            "/api/v1/run-management/{}/{}/checkpoints/{}/discard",
                            segment(&project_id)?,
                            segment(&run_id)?,
                            segment(&task_id)?
                        ),
                        true,
                    )?
                    .json(&serde_json::json!({ "revision": revision })),
                    Operation::Resource,
                )
                .await?;
                if response.status() != reqwest::StatusCode::NO_CONTENT {
                    return Err(AppError::InvalidResponse);
                }
                Ok(result("Checkpoint discarded", vec![]))
            }
            WorkspaceOperation::Checkpoints { project_id } => {
                let rows: Vec<Checkpoint> = Self::json(
                    self.request(
                        Method::GET,
                        &format!("/api/v1/projects/{}/checkpoints", segment(&project_id)?),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                if rows.len() > 256 {
                    return Err(AppError::InvalidResponse);
                }
                let mut items = Vec::new();
                for row in rows {
                    row.validate(&project_id, None)?;
                    items.push(row.item());
                }
                Ok(result("Checkpoints", items))
            }
            WorkspaceOperation::Checkpoint {
                project_id,
                task_id,
            } => Ok(result(
                "Checkpoint",
                vec![self.saved_checkpoint(&project_id, &task_id).await?.item()],
            )),
            WorkspaceOperation::CheckpointInspect {
                project_id,
                task_id,
            } => {
                let row: Reconciliation = Self::json(
                    self.request(
                        Method::GET,
                        &format!(
                            "/api/v1/projects/{}/checkpoints/{}/reconciliation",
                            segment(&project_id)?,
                            segment(&task_id)?
                        ),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                Ok(result(
                    "Checkpoint reconciliation",
                    vec![row.item(&project_id, &task_id)?],
                ))
            }
            WorkspaceOperation::CheckpointAbandon {
                project_id,
                task_id,
            } => {
                let row: Checkpoint = Self::json(
                    self.request(
                        Method::POST,
                        &format!(
                            "/api/v1/projects/{}/checkpoints/{}/abandon",
                            segment(&project_id)?,
                            segment(&task_id)?
                        ),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                row.validate(&project_id, Some(&task_id))?;
                if row.status != "ABANDONED" {
                    return Err(AppError::InvalidResponse);
                }
                Ok(result("Checkpoint abandoned", vec![row.item()]))
            }
            _ => Err(AppError::InvalidInput),
        }
    }
}
