use super::SseFrame;
use crate::domain::*;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolExecution {
    pub id: String,
    pub name: String,
    pub status: String,
    pub summary: String,
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
    messages: Vec<(String, String)>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WebEvent {
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
        }
    }
    pub fn assistant_text(&self) -> String {
        self.messages
            .iter()
            .map(|(_, v)| v.as_str())
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
            "message.delta" | "message.completed" => {
                let id = field("messageId")?;
                let text = field(if event.kind == "message.delta" {
                    "delta"
                } else {
                    "text"
                })?;
                if event.kind == "message.delta"
                    || event.payload["role"].as_str() == Some("assistant")
                {
                    let index = self
                        .messages
                        .iter()
                        .position(|(key, _)| key == &id)
                        .unwrap_or_else(|| {
                            self.messages.push((id, String::new()));
                            self.messages.len() - 1
                        });
                    if event.kind == "message.delta" {
                        self.messages[index].1.push_str(&text);
                    } else {
                        self.messages[index].1 = text;
                    }
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
                let summary = event.payload[if status == "RUNNING" {
                    "argumentsSummary"
                } else {
                    "resultSummary"
                }]
                .as_str()
                .unwrap_or("")
                .to_owned();
                self.tools.insert(
                    id.clone(),
                    ToolExecution {
                        id,
                        name,
                        status: status.into(),
                        summary,
                    },
                );
            }
            "working_set.item.added" => {
                let id = field("itemId")?;
                let item = WorkingSetItem {
                    id: id.clone(),
                    kind: field("kind")?,
                    identifier: field("identifier")?,
                    path: event.payload["path"].as_str().unwrap_or("").into(),
                };
                self.working_set.insert(id, item);
            }
            "working_set.item.removed" => {
                self.working_set.remove(&field("itemId")?);
            }
            _ => {}
        }
        self.last_sequence = Some(sequence);
        if self.status.terminal() {
            self.stream_state = StreamState::Closed;
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
        }
        Ok(())
    }
}
