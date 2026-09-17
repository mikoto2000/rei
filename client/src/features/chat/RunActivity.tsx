import type { Run } from "../../entities/models";

/** Receives presentation state from Rust, never raw AgentEvent envelopes. */
export function RunActivity({ run }: { run: Run }) {
  const activities = run.activities ?? [];
  const count = run.tools.length + activities.length;
  if (!count) return null;
  return (
    <details className="activity live-activity">
      <summary>Activity · {count}</summary>
      <div className="activity-content">
        {run.tools.map((tool) => (
          <div className="tool" key={tool.id}>
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
        ))}
        {activities.map((activity) => (
          <div className="activity-item" key={activity.id}>
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
              {activity.durationMs != null && (
                <span>{activity.durationMs} ms</span>
              )}
              {activity.metrics.map((metric) => (
                <span key={metric.label}>
                  {metric.label}: {metric.value}
                </span>
              ))}
            </div>
            {activity.error && (
              <p className="notice">{activity.error.message}</p>
            )}
          </div>
        ))}
      </div>
    </details>
  );
}
