use super::workspace::{result, segment};
use super::*;
use serde::Deserialize;
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Dependency {
    id: String,
    project_id: String,
    session_id: String,
    spec: Spec,
    state: String,
    reason: String,
    version: u64,
    answer: Option<String>,
    deadline: String,
    prerequisites: Vec<String>,
}
#[derive(Deserialize)]
struct Spec {
    kind: String,
    target: String,
}
impl Dependency {
    fn validate(&self, project: &str) -> Result<()> {
        if self.project_id != project
            || self.id.is_empty()
            || self.session_id.is_empty()
            || ![
                "RUNNING",
                "WAITING",
                "BLOCKED",
                "COMPLETED",
                "FAILED",
                "CANCELLED",
            ]
            .contains(&self.state.as_str())
            || self.prerequisites.len() > 16
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(())
    }
    fn item(self) -> WorkspaceItem {
        WorkspaceItem {
            id: Some(self.id),
            title: self.spec.target,
            fields: vec![
                ("Project".into(), self.project_id),
                ("Session".into(), self.session_id),
                ("Kind".into(), self.spec.kind),
                ("State".into(), self.state),
                ("Reason".into(), self.reason),
                ("Version".into(), self.version.to_string()),
                ("Answer".into(), self.answer.unwrap_or_default()),
                ("Deadline".into(), self.deadline),
                ("Prerequisites".into(), self.prerequisites.join("\n")),
            ],
        }
    }
}
impl HttpReiClient {
    pub(super) async fn dependency_operation(
        &self,
        op: WorkspaceOperation,
    ) -> Result<WorkspaceResult> {
        match op {
            WorkspaceOperation::Dependencies { project_id } => {
                let rows: Vec<Dependency> = Self::json(
                    self.request(
                        Method::GET,
                        &format!("/api/v1/projects/{}/dependencies", segment(&project_id)?),
                        true,
                    )?,
                    Operation::Resource,
                )
                .await?;
                if rows.len() > 256 {
                    return Err(AppError::InvalidResponse);
                }
                for row in &rows {
                    row.validate(&project_id)?;
                }
                Ok(result(
                    "Saved dependencies",
                    rows.into_iter().map(Dependency::item).collect(),
                ))
            }
            WorkspaceOperation::DependencyAnswer {
                project_id,
                id,
                expected_version,
                answer,
            } => {
                // Java's answer length contract counts UTF-16 code units.
                if answer.trim().is_empty()
                    || answer.encode_utf16().count() > 4096
                    || expected_version >= i64::MAX as u64
                {
                    return Err(AppError::InvalidResponse);
                }
                let row: Dependency = Self::json(
                    self.request(
                        Method::POST,
                        &format!(
                            "/api/v1/projects/{}/dependencies/{}/answer",
                            segment(&project_id)?,
                            segment(&id)?
                        ),
                        true,
                    )?
                    .json(&serde_json::json!({"expectedVersion":expected_version,"answer":answer})),
                    Operation::Resource,
                )
                .await?;
                row.validate(&project_id)?;
                if row.id != id
                    || row.spec.kind != "USER_ANSWER"
                    || row.version != expected_version + 1
                    || row
                        .answer
                        .as_ref()
                        .is_none_or(|value| value.trim().is_empty())
                {
                    return Err(AppError::InvalidResponse);
                }
                Ok(result("Answer saved", vec![row.item()]))
            }
            _ => Err(AppError::InvalidResponse),
        }
    }
}
