use super::workspace::{result, segment};
use super::*;
use serde::Deserialize;
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Notice {
    id: String,
    project_id: String,
    session_id: String,
    run_id: Option<String>,
    kind: String,
    reference: String,
    message: String,
    status: String,
    created_at: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Approval {
    id: String,
    project_id: String,
    session_id: String,
    run_id: String,
    tool: String,
    arguments_preview: String,
    status: String,
    expires_at: String,
}
impl Notice {
    fn item(self, project: &str) -> Result<WorkspaceItem> {
        if self.project_id != project || self.id.is_empty() || self.session_id.is_empty() {
            return Err(AppError::InvalidResponse);
        }
        Ok(WorkspaceItem {
            id: Some(self.id),
            title: self.kind,
            fields: vec![
                ("Message".into(), self.message),
                ("Status".into(), self.status),
                ("Project".into(), self.project_id),
                ("Session".into(), self.session_id),
                ("Run".into(), self.run_id.unwrap_or_default()),
                ("Reference".into(), self.reference),
                ("Created".into(), self.created_at),
            ],
        })
    }
}
impl Approval {
    fn item(self, project: &str) -> Result<WorkspaceItem> {
        if self.project_id != project
            || self.id.is_empty()
            || self.session_id.is_empty()
            || self.run_id.is_empty()
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(WorkspaceItem {
            id: Some(self.id),
            title: self.tool,
            fields: vec![
                ("Arguments".into(), self.arguments_preview),
                ("Status".into(), self.status),
                ("Project".into(), self.project_id),
                ("Session".into(), self.session_id),
                ("Run".into(), self.run_id),
                ("Expires".into(), self.expires_at),
            ],
        })
    }
}
impl HttpReiClient {
    pub(super) async fn attention_operation(
        &self,
        op: WorkspaceOperation,
    ) -> Result<WorkspaceResult> {
        match op {
            WorkspaceOperation::Attention { project_id } => {
                let rows: Vec<Notice> = Self::json(
                    self.request(
                        Method::GET,
                        &format!("/api/v1/projects/{}/attention", segment(&project_id)?),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                if rows.len() > 256 {
                    return Err(AppError::InvalidResponse);
                }
                Ok(result(
                    "Inbox",
                    rows.into_iter()
                        .map(|row| row.item(&project_id))
                        .collect::<Result<Vec<_>>>()?,
                ))
            }
            WorkspaceOperation::AttentionAck { project_id, id } => {
                let row: Notice = Self::json(
                    self.request(
                        Method::POST,
                        &format!(
                            "/api/v1/projects/{}/attention/{}/ack",
                            segment(&project_id)?,
                            segment(&id)?
                        ),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                if row.id != id || row.status != "ACKNOWLEDGED" {
                    return Err(AppError::InvalidResponse);
                }
                Ok(result(
                    "Notification acknowledged",
                    vec![row.item(&project_id)?],
                ))
            }
            WorkspaceOperation::Approvals { project_id } => {
                let rows: Vec<Approval> = Self::json(
                    self.request(
                        Method::GET,
                        &format!("/api/v1/projects/{}/approvals", segment(&project_id)?),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                if rows.len() > 256 {
                    return Err(AppError::InvalidResponse);
                }
                Ok(result(
                    "Approvals",
                    rows.into_iter()
                        .map(|row| row.item(&project_id))
                        .collect::<Result<Vec<_>>>()?,
                ))
            }
            WorkspaceOperation::ApprovalDecision {
                project_id,
                id,
                approved,
            } => {
                let row: Approval = Self::json(
                    self.request(
                        Method::POST,
                        &format!(
                            "/api/v1/projects/{}/approvals/{}/decision",
                            segment(&project_id)?,
                            segment(&id)?
                        ),
                        true,
                    )?
                    .json(&serde_json::json!({"approved":approved})),
                    Operation::Resource,
                )
                .await?;
                if row.id != id || row.status != (if approved { "APPROVED" } else { "DENIED" }) {
                    return Err(AppError::InvalidResponse);
                }
                Ok(result("Approval decision", vec![row.item(&project_id)?]))
            }
            _ => Err(AppError::InvalidInput),
        }
    }
}
