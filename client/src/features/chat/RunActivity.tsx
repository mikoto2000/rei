import type { Activity, Run, ToolExecution } from "../../entities/models";

export function ToolRow({ tool }: { tool: ToolExecution }) {
  return (
    <div className="tool">
      <span aria-hidden="true">
        {tool.status === "COMPLETED"
          ? "✓"
          : tool.status === "FAILED"
            ? "!"
            : "↻"}
      </span>
      <div>
        <strong>{tool.name}</strong>
        {tool.summary && <p>{tool.summary}</p>}
        {tool.durationMs != null && <p>{tool.durationMs} ms</p>}
        {tool.error && <p className="notice">{tool.error.message}</p>}
      </div>
      <small>{tool.status}</small>
    </div>
  );
}
export function ActivityRow({ activity }: { activity: Activity }) {
  return (
    <div className="activity-item">
      <div className="activity-heading">
        <strong>
          {activity.category} · {activity.label}
        </strong>
        <small>{activity.status}</small>
      </div>
      {activity.summary && <p>{activity.summary}</p>}
      <div className="activity-metrics">
        {activity.firstTokenMs != null && (
          <span>First token: {activity.firstTokenMs} ms</span>
        )}
        {activity.durationMs != null && <span>{activity.durationMs} ms</span>}
        {activity.metrics.map((metric) => (
          <span key={metric.label}>
            {metric.label}: {metric.value}
          </span>
        ))}
      </div>
      {activity.error && <p className="notice">{activity.error.message}</p>}
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
