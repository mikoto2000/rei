import type { Activity, Run, ToolExecution } from "../../entities/models";

// Presentation formatting only: all lifecycle interpretation stays in Rust.
const oneLine = (text: string) => text.replace(/\s+/g, " ").trim();
export function ToolRow({ tool }: { tool: ToolExecution }) {
  const mark =
    tool.status === "COMPLETED" ? "✓" : tool.status === "FAILED" ? "✗" : "→";
  return (
    <div
      className="event-line tool-event-line"
      aria-label={`${tool.name}: ${tool.status.toLowerCase()}`}
    >
      {mark} <span>{tool.name}</span>
      {tool.summary && (
        <>
          {" "}
          <span>{oneLine(tool.summary)}</span>
        </>
      )}
      {tool.durationMs != null && (
        <>
          {" "}
          <span>{tool.durationMs} ms</span>
        </>
      )}
      {tool.error && (
        <>
          {" "}
          <span>{oneLine(tool.error.message)}</span>
        </>
      )}
    </div>
  );
}
export function ActivityRow({ activity }: { activity: Activity }) {
  return (
    <div className="event-line">
      [{activity.category.toLowerCase()}] <span>{activity.label}</span>{" "}
      <span>{activity.status.toLowerCase()}</span>
      {activity.summary && (
        <>
          {" "}
          <span>{oneLine(activity.summary)}</span>
        </>
      )}
      {activity.firstTokenMs != null && (
        <>
          {" "}
          <span>First token: {activity.firstTokenMs} ms</span>
        </>
      )}
      {activity.durationMs != null && (
        <>
          {" "}
          <span>{activity.durationMs} ms</span>
        </>
      )}
      {activity.metrics.map((metric) => (
        <span key={metric.label}>
          {" "}
          <span>
            {metric.label}: {metric.value}
          </span>
        </span>
      ))}
      {activity.error && (
        <>
          {" "}
          <span>{oneLine(activity.error.message)}</span>
        </>
      )}
    </div>
  );
}
/** Compatibility for snapshots produced before the ordered timeline DTO existed. */
export function RunActivity({ run }: { run: Run }) {
  const activities = run.activities ?? [];
  const count = run.tools.length + activities.length;
  if (!count) return null;
  return (
    <details className="activity live-activity">
      <summary>Activity · {count}</summary>
      <div className="activity-content">
        {run.tools.map((tool) => (
          <ToolRow key={tool.id} tool={tool} />
        ))}
        {activities.map((activity) => (
          <ActivityRow key={activity.id} activity={activity} />
        ))}
      </div>
    </details>
  );
}
export function RunTimeline({ run }: { run: Run }) {
  return (
    <div className="run-timeline">
      {run.timeline?.map((entry) =>
        entry.kind === "text" ? (
          <div className="answer" key={entry.id}>
            {entry.text}
          </div>
        ) : entry.kind === "tool" ? (
          <ToolRow key={entry.id} tool={entry.tool} />
        ) : (
          <ActivityRow key={entry.id} activity={entry.activity} />
        ),
      )}
    </div>
  );
}
