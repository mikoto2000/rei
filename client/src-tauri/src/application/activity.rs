use super::ActivityError;
use crate::domain::{AppError, Result};
use serde::Serialize;
use serde_json::Value;

/// Presentation DTOs, never persisted. React does not receive the event payload.
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Activity {
    pub id: String,
    pub category: String,
    pub label: String,
    pub status: String,
    pub summary: String,
    pub started_at: Option<String>,
    pub completed_at: Option<String>,
    pub duration_ms: Option<u64>,
    pub first_token_ms: Option<u64>,
    pub error: Option<ActivityError>,
    pub metrics: Vec<ActivityMetric>,
}
#[derive(Clone, Serialize)]
pub struct ActivityMetric {
    pub label: String,
    pub value: u64,
}
impl Activity {
    pub(super) fn new(id: String, category: &str, label: &str) -> Self {
        Self {
            id,
            category: category.into(),
            label: label.into(),
            status: "COMPLETED".into(),
            summary: String::new(),
            started_at: None,
            completed_at: None,
            duration_ms: None,
            first_token_ms: None,
            error: None,
            metrics: Vec::new(),
        }
    }
}

pub(super) fn reduce_activity(
    activities: &mut Vec<Activity>,
    kind: &str,
    sequence: u64,
    timestamp: &Option<String>,
    correlation: Option<&str>,
    payload: &Value,
) -> Result<Option<Activity>> {
    let (category, label, key) = match kind {
        "llm.request.started"
        | "llm.request.failed"
        | "llm.response.first_token"
        | "llm.response.completed" => ("LLM", "LLM request", Some("requestId")),
        "stagnation.updated" => ("Progress", "No progress", None),
        "stagnation.detected" => ("Progress", "Stagnation detected", None),
        "stagnation.replan_requested" => ("Progress", "Replanning", None),
        "stagnation.recovered" => ("Progress", "Progress recovered", None),
        "stagnation.stopped" => ("Progress", "Stopped after stagnation", None),
        "progress.detected" => ("Progress", "Progress detected", None),
        "working_set.search.started" | "working_set.search.completed" => {
            ("Working Set", "Search", Some("searchId"))
        }
        "working_set.context.injected" => ("Working Set", "Context injected", None),
        "skill.selection.started" | "skill.selection.completed" | "skill.selection.failed" => {
            ("Skill", "Selection", Some("selectionId"))
        }
        "skill.routing.started"
        | "skill.routing.completed"
        | "skill.routing.failed"
        | "skill.candidates.evaluated" => ("Skill", "Routing", Some("correlationId")),
        "thinking.started" | "thinking.delta" | "thinking.completed" => {
            ("Thinking", "Thinking", Some("thinkingId"))
        }
        _ => return Ok(None),
    };
    let key = match key {
        Some("correlationId") => correlation.ok_or(AppError::InvalidResponse)?.to_owned(),
        Some(key) => payload[key]
            .as_str()
            .ok_or(AppError::InvalidResponse)?
            .to_owned(),
        None => sequence.to_string(),
    };
    let id = format!("{category}:{label}:{key}");
    let index = activities
        .iter()
        .position(|a| a.id == id)
        .unwrap_or_else(|| {
            activities.push(Activity::new(id, category, label));
            activities.len() - 1
        });
    let activity = &mut activities[index];
    if kind.ends_with(".started") {
        activity.status = "RUNNING".into();
        activity.started_at = timestamp.clone();
    } else if kind.ends_with(".first_token") {
        activity.status = "RUNNING".into();
        activity.first_token_ms = payload["durationMs"].as_u64();
    } else if kind == "skill.candidates.evaluated" || kind == "thinking.delta" {
        // Intermediate observations do not finish the correlated lifecycle.
        activity.status = "RUNNING".into();
    } else {
        activity.status = if kind.ends_with(".failed") {
            "FAILED"
        } else {
            "COMPLETED"
        }
        .into();
        activity.completed_at = timestamp.clone();
        activity.duration_ms = payload["durationMs"].as_u64();
    }
    if let Some(feature) = payload["feature"].as_str() {
        activity.summary = feature.into();
    }
    if category == "Skill" {
        let names: Vec<&str> = ["explicitSkillNames", "implicitSkillNames"]
            .iter()
            .filter_map(|key| payload[key].as_array())
            .flatten()
            .filter_map(Value::as_str)
            .collect();
        if !names.is_empty() {
            activity.summary = names.join(", ");
        } else if let Some(name) = payload["selectedSkill"]
            .as_str()
            .or_else(|| payload["actualSelectedSkill"].as_str())
        {
            activity.summary = name.into();
        }
    }
    activity.error = serde_json::from_value(payload["error"].clone()).ok();
    for (key, label) in [
        ("consecutiveNoProgressIterations", "No progress"),
        ("threshold", "Threshold"),
        ("stagnationReplanCount", "Replans"),
        ("maxStagnationReplans", "Max replans"),
        ("hitCount", "Hits"),
        ("candidateCount", "Candidates"),
        ("selectedCount", "Selected"),
        ("alreadyPresentCount", "Already present"),
        ("workingSetSizeBefore", "Items before"),
        ("workingSetSizeAfter", "Items after"),
        ("itemCount", "Items"),
        ("contextCharacters", "Context characters"),
        ("totalSkillCount", "Total skills"),
        ("routingInvocation", "Invocation"),
        ("selectorDurationMs", "Selector ms"),
        ("metadataLoadDurationMs", "Metadata ms"),
        ("skillLoadDurationMs", "Skill load ms"),
    ] {
        if let Some(value) = payload[key].as_u64() {
            activity.metrics.retain(|m| m.label != label);
            activity.metrics.push(ActivityMetric {
                label: label.into(),
                value,
            });
        }
    }
    Ok(if kind == "thinking.delta" {
        None
    } else {
        Some(activity.clone())
    })
}
