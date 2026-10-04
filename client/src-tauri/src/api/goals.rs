use super::workspace::{result, segment};
use super::*;
use serde::Deserialize;
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Goal {
    id: String,
    project_id: String,
    session_id: String,
    objective: String,
    status: String,
    current_run_id: Option<String>,
    reason: String,
    max_runs: u32,
    max_llm_calls: u32,
    attempts: u32,
    llm_calls_used: u32,
    criteria: Vec<Criterion>,
}
#[derive(Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct Criterion {
    relative_file: String,
    sha256: String,
}
#[derive(Deserialize)]
struct Inspection {
    goal: Goal,
    verification: Verification,
}
#[derive(Deserialize)]
struct Verification {
    satisfied: bool,
    reason: String,
}
#[derive(Deserialize)]
struct History {
    history: Vec<serde_json::Value>,
    attempts: Vec<serde_json::Value>,
}
impl Goal {
    fn validate(&self, project: &str, id: Option<&str>) -> Result<()> {
        if self.project_id != project
            || self.id.is_empty()
            || self.session_id.is_empty()
            || id.is_some_and(|id| id != self.id)
            || self.criteria.is_empty()
            || self.criteria.len() > 16
            || !(1..=10).contains(&self.max_runs)
            || !(1..=100).contains(&self.max_llm_calls)
            || self.attempts > self.max_runs
            || self.llm_calls_used > self.max_llm_calls
            || ![
                "READY",
                "RUNNING",
                "WAITING_APPROVAL",
                "BLOCKED",
                "FAILED",
                "PAUSED",
                "COMPLETED",
                "CANCELLED",
            ]
            .contains(&self.status.as_str())
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(())
    }
    fn item(self) -> WorkspaceItem {
        WorkspaceItem {
            id: Some(self.id),
            title: self.objective,
            fields: vec![
                ("Project".into(), self.project_id),
                ("Session".into(), self.session_id),
                ("Status".into(), self.status),
                ("Run".into(), self.current_run_id.unwrap_or_default()),
                ("Reason".into(), self.reason),
                (
                    "Runs".into(),
                    format!("{} / {}", self.attempts, self.max_runs),
                ),
                (
                    "LLM calls".into(),
                    format!("{} / {}", self.llm_calls_used, self.max_llm_calls),
                ),
                (
                    "Criteria".into(),
                    serde_json::to_string_pretty(&self.criteria).unwrap_or_default(),
                ),
            ],
        }
    }
}
impl HttpReiClient {
    pub(super) async fn saved_goal_snapshot(&self, project: &str, id: &str) -> Result<RunSnapshot> {
        let goal: Goal = Self::json(
            self.request(
                Method::GET,
                &format!(
                    "/api/v1/projects/{}/goals/{}",
                    segment(project)?,
                    segment(id)?
                ),
                true,
            )?,
            Operation::Resource,
        )
        .await?;
        goal.validate(project, Some(id))?;
        let run = goal
            .current_run_id
            .as_deref()
            .filter(|id| !id.is_empty())
            .ok_or(AppError::RunNotFound)?;
        let snapshot = self.run(run).await?;
        if snapshot.run_id != run
            || snapshot.project_id != project
            || snapshot.session_id != goal.session_id
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(snapshot)
    }
    pub(super) async fn goal_operation(&self, op: WorkspaceOperation) -> Result<WorkspaceResult> {
        if let WorkspaceOperation::Goals { project_id } = op {
            let rows: Vec<Goal> = Self::json(
                self.request(
                    Method::GET,
                    &format!("/api/v1/projects/{}/goals", segment(&project_id)?),
                    true,
                )?,
                Operation::Resource,
            )
            .await?;
            if rows.len() > 256 {
                return Err(AppError::InvalidResponse);
            }
            for row in &rows {
                row.validate(&project_id, None)?;
            }
            return Ok(result("Goals", rows.into_iter().map(Goal::item).collect()));
        }
        let (project, id, action, body) = match op {
            WorkspaceOperation::Goal { project_id, id } => (project_id, id, "", None),
            WorkspaceOperation::GoalHistory { project_id, id } => (project_id, id, "history", None),
            WorkspaceOperation::GoalVerify { project_id, id } => (project_id, id, "verify", None),
            WorkspaceOperation::GoalRun { project_id, id } => (project_id, id, "run", None),
            WorkspaceOperation::GoalCancel { project_id, id } => (project_id, id, "cancel", None),
            WorkspaceOperation::GoalReconcile {
                project_id,
                id,
                expected_run_id,
                acknowledge_uncertain_side_effects,
            } => {
                if !acknowledge_uncertain_side_effects || expected_run_id.trim().is_empty() {
                    return Err(AppError::InvalidInput);
                }
                (
                    project_id,
                    id,
                    "reconcile",
                    Some(
                        serde_json::json!({"expectedRunId":expected_run_id,"acknowledgeUncertainSideEffects":true}),
                    ),
                )
            }
            _ => return Err(AppError::InvalidInput),
        };
        let base = format!(
            "/api/v1/projects/{}/goals/{}",
            segment(&project)?,
            segment(&id)?
        );
        if action == "history" {
            let owner: Goal =
                Self::json(self.request(Method::GET, &base, true)?, Operation::Resource).await?;
            owner.validate(&project, Some(&id))?;
            let history: History = Self::json(
                self.request(Method::GET, &format!("{base}/history"), true)?,
                Operation::Resource,
            )
            .await?;
            if history.history.len() > 256 || history.attempts.len() > 10 {
                return Err(AppError::InvalidResponse);
            }
            let mut item = owner.item();
            item.fields.push((
                "History".into(),
                serde_json::to_string_pretty(&history.history).unwrap_or_default(),
            ));
            item.fields.push((
                "Attempts".into(),
                serde_json::to_string_pretty(&history.attempts).unwrap_or_default(),
            ));
            return Ok(result("Goal history", vec![item]));
        }
        let mut request = self.request(
            if action.is_empty() {
                Method::GET
            } else {
                Method::POST
            },
            &if action.is_empty() {
                base
            } else {
                format!("{base}/{action}")
            },
            true,
        )?;
        if let Some(body) = &body {
            request = request.json(body);
        }
        if action == "verify" {
            let inspection: Inspection = Self::json(request, Operation::Resource).await?;
            inspection.goal.validate(&project, Some(&id))?;
            let mut item = inspection.goal.item();
            item.fields.push((
                "Satisfied".into(),
                inspection.verification.satisfied.to_string(),
            ));
            item.fields
                .push(("Verification".into(), inspection.verification.reason));
            return Ok(result("Goal verification", vec![item]));
        }
        let goal: Goal = Self::json(request, Operation::Resource).await?;
        goal.validate(&project, Some(&id))?;
        if action == "cancel" && goal.status != "CANCELLED" {
            return Err(AppError::InvalidResponse);
        }
        if action == "reconcile" {
            let expected = body
                .as_ref()
                .and_then(|body| body["expectedRunId"].as_str())
                .ok_or(AppError::InvalidResponse)?;
            if goal.status != "PAUSED"
                || goal.current_run_id.as_deref()
                    != if expected == "none" {
                        None
                    } else {
                        Some(expected)
                    }
            {
                return Err(AppError::InvalidResponse);
            }
        }
        Ok(result("Goal", vec![goal.item()]))
    }
}
