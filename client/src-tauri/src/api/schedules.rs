use super::workspace::{result, segment};
use super::*;
use serde::Deserialize;
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Task {
    id: String,
    created_at: String,
    execute_at: String,
    action: String,
    conversation_id: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Schedule {
    task: Task,
    project_id: String,
    status: String,
    run_id: Option<String>,
    outcome: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Inspection {
    schedule: Schedule,
    interval: Option<serde_json::Value>,
    cron: Option<serde_json::Value>,
    event_trigger: Option<serde_json::Value>,
}
impl Schedule {
    fn validate(&self, project: &str, id: Option<&str>) -> Result<()> {
        if self.project_id != project
            || self.task.id.is_empty()
            || self.task.conversation_id.is_empty()
            || self.task.action.trim().is_empty()
            || self.task.action.encode_utf16().count() > 4096
            || id.is_some_and(|id| id != self.task.id)
            || ![
                "PENDING",
                "SCHEDULED",
                "WAITING_EVENT",
                "RUNNING",
                "COMPLETED",
                "FAILED",
                "CANCELLED",
            ]
            .contains(&self.status.as_str())
            || (self.status == "RUNNING" && self.run_id.as_deref().is_none_or(str::is_empty))
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(())
    }
    fn item(self) -> WorkspaceItem {
        WorkspaceItem {
            id: Some(self.task.id),
            title: self.task.action,
            fields: vec![
                ("Project".into(), self.project_id),
                ("Session".into(), self.task.conversation_id),
                ("Status".into(), self.status),
                ("Run".into(), self.run_id.unwrap_or_default()),
                ("Created".into(), self.task.created_at),
                ("Due".into(), self.task.execute_at),
                ("Outcome".into(), self.outcome),
            ],
        }
    }
}
impl Inspection {
    fn item(self) -> WorkspaceItem {
        let mut item = self.schedule.item();
        for (name, value) in [
            ("Interval", self.interval),
            ("Cron", self.cron),
            ("Event trigger", self.event_trigger),
        ] {
            if let Some(value) = value {
                item.fields.push((
                    name.into(),
                    serde_json::to_string_pretty(&value).unwrap_or_default(),
                ));
            }
        }
        item
    }
}
impl HttpReiClient {
    async fn schedule_inspection(&self, project: &str, id: &str) -> Result<Inspection> {
        let state: Inspection = Self::json(
            self.request(
                Method::GET,
                &format!(
                    "/api/v1/projects/{}/schedules/{}",
                    segment(project)?,
                    segment(id)?
                ),
                true,
            )?,
            Operation::Resource,
        )
        .await?;
        state.schedule.validate(project, Some(id))?;
        Ok(state)
    }
    pub(super) async fn saved_schedule_snapshot(
        &self,
        project: &str,
        id: &str,
    ) -> Result<RunSnapshot> {
        let state = self.schedule_inspection(project, id).await?.schedule;
        let run = state
            .run_id
            .as_deref()
            .filter(|run| !run.is_empty())
            .ok_or(AppError::RunNotFound)?;
        let snapshot = self.run(run).await?;
        if snapshot.run_id != run
            || snapshot.project_id != project
            || snapshot.session_id != state.task.conversation_id
        {
            return Err(AppError::InvalidResponse);
        }
        Ok(snapshot)
    }
    pub(super) async fn schedule_operation(
        &self,
        op: WorkspaceOperation,
    ) -> Result<WorkspaceResult> {
        if let WorkspaceOperation::Schedules { project_id } = op {
            let rows: Vec<Schedule> = Self::json(
                self.request(
                    Method::GET,
                    &format!("/api/v1/projects/{}/schedules", segment(&project_id)?),
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
            return Ok(result(
                "Schedules",
                rows.into_iter().map(Schedule::item).collect(),
            ));
        }
        let (project, id, action, expected) = match op {
            WorkspaceOperation::Schedule { project_id, id } => (project_id, id, "", None),
            WorkspaceOperation::ScheduleHistory { project_id, id } => {
                (project_id, id, "history", None)
            }
            WorkspaceOperation::ScheduleActivate { project_id, id } => {
                (project_id, id, "activate", None)
            }
            WorkspaceOperation::ScheduleCancel { project_id, id } => {
                (project_id, id, "cancel", None)
            }
            WorkspaceOperation::ScheduleReconcile {
                project_id,
                id,
                expected_run_id,
                acknowledge_uncertain_side_effects,
            } => {
                if !acknowledge_uncertain_side_effects
                    || expected_run_id.trim().is_empty()
                    || expected_run_id.len() > 128
                {
                    return Err(AppError::InvalidInput);
                }
                (project_id, id, "reconcile", Some(expected_run_id))
            }
            _ => return Err(AppError::InvalidInput),
        };
        if action.is_empty() {
            return Ok(result(
                "Schedule",
                vec![self.schedule_inspection(&project, &id).await?.item()],
            ));
        }
        let base = format!(
            "/api/v1/projects/{}/schedules/{}",
            segment(&project)?,
            segment(&id)?
        );
        if action == "history" {
            let mut item = self.schedule_inspection(&project, &id).await?.item();
            let history: Vec<serde_json::Value> = Self::json(
                self.request(Method::GET, &format!("{base}/history"), true)?,
                Operation::Resource,
            )
            .await?;
            if history.len() > 512 {
                return Err(AppError::InvalidResponse);
            }
            item.fields.push((
                "History".into(),
                serde_json::to_string_pretty(&history).unwrap_or_default(),
            ));
            return Ok(result("Schedule history", vec![item]));
        }
        let mut request = self.request(Method::POST, &format!("{base}/{action}"), true)?;
        if let Some(expected) = &expected {
            request=request.json(&serde_json::json!({"expectedRunId":expected,"acknowledgeUncertainSideEffects":true}));
        }
        let item = if action == "reconcile" {
            let state: Schedule = Self::json(request, Operation::Resource).await?;
            state.validate(&project, Some(&id))?;
            if state.status != "FAILED"
                || state.run_id.as_deref() != expected.as_deref()
                || state.outcome != "uncertain_run_reconciled"
            {
                return Err(AppError::InvalidResponse);
            }
            state.item()
        } else {
            let state: Inspection = Self::json(request, Operation::Resource).await?;
            state.schedule.validate(&project, Some(&id))?;
            if action == "cancel" && state.schedule.status != "CANCELLED" {
                return Err(AppError::InvalidResponse);
            }
            if action == "activate" && state.schedule.status == "PENDING" {
                return Err(AppError::InvalidResponse);
            }
            state.item()
        };
        Ok(result("Schedule", vec![item]))
    }
}
