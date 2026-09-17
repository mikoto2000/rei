use super::{Activity, SseFrame};
use crate::domain::*;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;

#[derive(Clone, Serialize)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum TimelineEntry {
    Text {
        id: String,
        message_id: String,
        text: String,
    },
    Tool {
        id: String,
        tool: ToolExecution,
    },
    Activity {
        id: String,
        activity: Activity,
    },
}

fn append_text(timeline: &mut Vec<TimelineEntry>, sequence: u64, message_id: &str, text: &str) {
    if text.is_empty() {
        return;
    }
    if let Some(TimelineEntry::Text {
        message_id: previous,
        text: body,
        ..
    }) = timeline.last_mut()
    {
        if previous == message_id {
            body.push_str(text);
            return;
        }
    }
    timeline.push(TimelineEntry::Text {
        id: sequence.to_string(),
        message_id: message_id.into(),
        text: text.into(),
    });
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolExecution {
    pub id: String,
    pub name: String,
    pub status: String,
    pub summary: String,
    pub started_at: Option<String>,
    pub completed_at: Option<String>,
    pub duration_ms: Option<u64>,
    pub error: Option<ActivityError>,
}
#[derive(Clone, Serialize, Deserialize)]
pub struct ActivityError {
    #[serde(rename = "type", default)]
    pub kind: String,
    pub message: String,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageProjection {
    pub message_id: String,
    pub role: String,
    pub text: String,
    pub completed: bool,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkingSetItem {
    pub id: String,
    pub kind: String,
    pub identifier: String,
    pub path: String,
}
#[derive(Clone)]
pub struct Projection {
    pub(super) registered_order: usize,
    pub revision: u64,
    pub server_id: String,
    pub conversation_id: String,
    pub project_id: String,
    pub run_id: String,
    pub session_id: String,
    pub turn_id: String,
    pub prompt: String,
    pub status: RunStatus,
    pub stream_state: StreamState,
    pub last_sequence: Option<u64>,
    pub incomplete: bool,
    pub error: Option<AppError>,
    pub failure: Option<String>,
    pub tools: BTreeMap<String, ToolExecution>,
    pub working_set: BTreeMap<String, WorkingSetItem>,
    pub messages: Vec<MessageProjection>,
    pub activities: Vec<Activity>,
    pub timeline: Vec<TimelineEntry>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WebEvent {
    timestamp: Option<String>,
    correlation_id: Option<String>,
    session_id: Option<String>,
    turn_id: Option<String>,
    project_id: Option<String>,
    sequence: u64,
    run_id: String,
    #[serde(rename = "type")]
    kind: String,
    version: u32,
    payload: Value,
}
impl Projection {
    pub fn new(
        server: &str,
        conversation: &str,
        project: &str,
        receipt: ChatReceipt,
        prompt: &str,
    ) -> Self {
        Self {
            registered_order: 0,
            revision: 0,
            server_id: server.into(),
            conversation_id: conversation.into(),
            project_id: project.into(),
            run_id: receipt.run_id,
            session_id: receipt.session_id,
            turn_id: receipt.turn_id,
            prompt: prompt.into(),
            status: RunStatus::Queued,
            stream_state: StreamState::Connecting,
            last_sequence: None,
            incomplete: false,
            error: None,
            failure: None,
            tools: BTreeMap::new(),
            working_set: BTreeMap::new(),
            messages: Vec::new(),
            activities: Vec::new(),
            timeline: Vec::new(),
        }
    }
    pub fn assistant_text(&self) -> String {
        self.messages
            .iter()
            .filter(|m| m.role == "assistant")
            .map(|m| m.text.as_str())
            .collect::<Vec<_>>()
            .join("\n\n")
    }
    pub fn apply(&mut self, frame: SseFrame) -> Result<bool> {
        if frame.event == "heartbeat" || self.status.terminal() {
            return Ok(false);
        }
        let event: WebEvent =
            serde_json::from_str(&frame.data).map_err(|_| AppError::InvalidResponse)?;
        let sequence = frame
            .id
            .as_deref()
            .ok_or(AppError::InvalidResponse)?
            .parse::<u64>()
            .map_err(|_| AppError::InvalidResponse)?;
        if event.version != 1
            || event.run_id != self.run_id
            || event.sequence != sequence
            || frame.event != event.kind
            || event
                .session_id
                .as_ref()
                .is_some_and(|id| id != &self.session_id)
            || event.turn_id.as_ref().is_some_and(|id| id != &self.turn_id)
            || event
                .project_id
                .as_ref()
                .is_some_and(|id| id != &self.project_id)
        {
            return Err(AppError::InvalidResponse);
        }
        if self.last_sequence.is_some_and(|last| sequence <= last) {
            return Ok(false);
        }
        let field = |name: &str| {
            event.payload[name]
                .as_str()
                .map(str::to_owned)
                .ok_or(AppError::InvalidResponse)
        };
        match event.kind.as_str() {
            "agent.run.started" => self.status = RunStatus::Running,
            "agent.run.completed" => self.status = RunStatus::Completed,
            "agent.run.failed" => {
                self.status = RunStatus::Failed;
                self.failure = Some("Run failed".into());
            }
            "agent.run.cancelled" => self.status = RunStatus::Cancelled,
            "message.started" | "message.delta" | "message.completed" => {
                let id = field("messageId")?;
                let text = if event.kind == "message.started" {
                    String::new()
                } else {
                    field(if event.kind == "message.delta" {
                        "delta"
                    } else {
                        "text"
                    })?
                };
                let role = event.payload["role"].as_str().unwrap_or("assistant");
                let index = self
                    .messages
                    .iter()
                    .position(|m| m.message_id == id)
                    .unwrap_or_else(|| {
                        self.messages.push(MessageProjection {
                            message_id: id,
                            role: role.into(),
                            text: String::new(),
                            completed: false,
                        });
                        self.messages.len() - 1
                    });
                let message = &mut self.messages[index];
                if event.kind == "message.delta" {
                    if !message.completed {
                        message.text.push_str(&text);
                        if message.role == "assistant" {
                            append_text(&mut self.timeline, sequence, &message.message_id, &text);
                        }
                    }
                } else if event.kind == "message.completed" {
                    if role == "assistant" {
                        if let Some(suffix) = text.strip_prefix(&message.text) {
                            append_text(&mut self.timeline, sequence, &message.message_id, suffix);
                        } else {
                            self.timeline.retain(|entry| !matches!(entry, TimelineEntry::Text { message_id, .. } if message_id == &message.message_id));
                            append_text(&mut self.timeline, sequence, &message.message_id, &text);
                        }
                    } else {
                        self.timeline.retain(|entry| !matches!(entry, TimelineEntry::Text { message_id, .. } if message_id == &message.message_id));
                    }
                    message.text = text;
                    message.role = role.into();
                    message.completed = true;
                }
            }
            "tool.started" | "tool.completed" | "tool.failed" => {
                let id = field("toolCallId")?;
                let name = field("toolName")?;
                let status = match event.kind.as_str() {
                    "tool.started" => "RUNNING",
                    "tool.completed" => "COMPLETED",
                    _ => "FAILED",
                };
                let tool = self
                    .tools
                    .entry(id.clone())
                    .or_insert_with(|| ToolExecution {
                        id,
                        name: name.clone(),
                        status: status.into(),
                        summary: String::new(),
                        started_at: None,
                        completed_at: None,
                        duration_ms: None,
                        error: None,
                    });
                tool.name = name;
                tool.status = status.into();
                if let Some(summary) = event.payload["summary"].as_str() {
                    tool.summary = summary.into();
                }
                if status == "RUNNING" {
                    tool.started_at = event.timestamp.clone();
                } else {
                    tool.completed_at = event.timestamp.clone();
                }
                if let Some(duration) = event.payload["duration"].as_u64() {
                    tool.duration_ms = Some(duration);
                }
                tool.error = serde_json::from_value(event.payload["error"].clone()).ok();
                self.timeline.push(TimelineEntry::Tool {
                    id: sequence.to_string(),
                    tool: tool.clone(),
                });
            }
            "working_set.item.added" => {
                let id = field("itemId")?;
                let item = WorkingSetItem {
                    id: id.clone(),
                    kind: field("kind")?,
                    identifier: field("identifier")?,
                    path: event.payload["path"].as_str().unwrap_or("").into(),
                };
                let mut activity = Activity::new(sequence.to_string(), "Working Set", "Added");
                activity.summary = item.identifier.clone();
                activity.completed_at = event.timestamp.clone();
                self.timeline.push(TimelineEntry::Activity {
                    id: sequence.to_string(),
                    activity: activity.clone(),
                });
                self.activities.push(activity);
                self.working_set.insert(id, item);
            }
            "working_set.item.removed" => {
                let id = field("itemId")?;
                let mut activity = Activity::new(sequence.to_string(), "Working Set", "Removed");
                activity.summary = self
                    .working_set
                    .remove(&id)
                    .map(|i| i.identifier)
                    .unwrap_or(id);
                activity.completed_at = event.timestamp.clone();
                self.timeline.push(TimelineEntry::Activity {
                    id: sequence.to_string(),
                    activity: activity.clone(),
                });
                self.activities.push(activity);
            }
            _ => {
                if let Some(activity) = super::activity::reduce_activity(
                    &mut self.activities,
                    &event.kind,
                    sequence,
                    &event.timestamp,
                    event.correlation_id.as_deref(),
                    &event.payload,
                )? {
                    self.timeline.push(TimelineEntry::Activity {
                        id: sequence.to_string(),
                        activity,
                    });
                }
            }
        }
        if matches!(
            event.kind.as_str(),
            "agent.run.started"
                | "agent.run.completed"
                | "agent.run.failed"
                | "agent.run.cancelled"
        ) {
            let mut activity = Activity::new(sequence.to_string(), "Run", "Run");
            activity.status = match self.status {
                RunStatus::Queued => "QUEUED",
                RunStatus::Running => "RUNNING",
                RunStatus::Completed => "COMPLETED",
                RunStatus::Failed => "FAILED",
                RunStatus::Cancelled => "CANCELLED",
            }
            .into();
            activity.duration_ms = event.payload["duration"].as_u64();
            self.timeline.push(TimelineEntry::Activity {
                id: sequence.to_string(),
                activity,
            });
        }
        self.last_sequence = Some(sequence);
        if self.status.terminal() {
            self.stream_state = StreamState::Closed;
            self.error = None;
        }
        Ok(true)
    }
    pub fn recover(&mut self, snapshot: RunSnapshot) -> Result<()> {
        if snapshot.run_id != self.run_id
            || snapshot.project_id != self.project_id
            || snapshot.session_id != self.session_id
        {
            return Err(AppError::InvalidResponse);
        }
        if !self.status.terminal() {
            self.status = snapshot.status;
        }
        if self.status == RunStatus::Failed {
            self.failure = Some("Run failed".into());
        }
        if self.status.terminal() {
            self.stream_state = StreamState::Closed;
            self.error = None;
        }
        Ok(())
    }
}
