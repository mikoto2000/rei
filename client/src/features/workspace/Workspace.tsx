import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import {
  errorText,
  activeRuns,
  type Project,
  type Run,
} from "../../entities/models";
import type {
  WorkspaceResult,
  WorkspaceOperation,
  BackgroundOperation,
} from "../../entities/workspace";
import { operations, buildOperation } from "./operations";
import { RunActivity, RunTimeline } from "../chat/RunActivity";
import type { ArtifactSelection } from "../../entities/artifacts";

export function Workspace({
  call,
  serverId,
  projects,
  runs,
  onAccepted,
  onArtifacts,
  initialProjectId,
  initialSessionId,
}: {
  initialProjectId?: string;
  initialSessionId?: string;
  call: Command;
  serverId: string | null;
  projects: Project[];
  runs: Run[];
  onAccepted: (run: Run) => void;
  onArtifacts?: (selection: ArtifactSelection) => void;
}) {
  const [action, setAction] = useState("feeds");
  const [values, setValues] = useState<Record<string, string>>({});
  const [project, setProject] = useState(
    initialProjectId ?? projects[0]?.id ?? "",
  );
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<WorkspaceResult | null>(null);
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setResult(null);
    setError(null);
    setPending(false);
    setProject(initialProjectId ?? projects[0]?.id ?? "");
    setValues({});
    return () => {
      generation.current++;
    };
  }, [serverId, projects, initialProjectId]);
  const spec = operations.find((s) => s.id === action)!;
  const [targets, setTargets] = useState<WorkspaceResult["items"]>([]);
  const [sessions, setSessions] = useState<
    { sessionId: string; title: string }[]
  >([]);
  const [targetError, setTargetError] = useState(false);
  const collection = ["feed", "updateFeed", "deleteFeed"].includes(action)
    ? "feeds"
    : ["memory", "deleteMemory"].includes(action)
      ? "memories"
      : ["reminder", "deleteReminder"].includes(action)
        ? "reminders"
        : action === "skill"
          ? "skills"
          : null;
  useEffect(() => {
    let disposed = false;
    setTargets([]);
    setSessions([]);
    setTargetError(false);
    if (!serverId) return;
    if (collection)
      void call("workspace_execute", {
        serverId,
        operation: { operation: collection },
      })
        .then((result) => {
          if (!disposed) setTargets(result.items);
        })
        .catch(() => {
          if (!disposed) setTargetError(true);
        });
    if (action === "workContextUpdate")
      void call("session_list", {
        serverId,
        projectId: project || null,
        limit: 100,
        cursor: null,
      })
        .then((result) => {
          if (!disposed) setSessions(result.items);
        })
        .catch(() => {
          if (!disposed) setTargetError(true);
        });
    return () => {
      disposed = true;
    };
  }, [call, serverId, collection, action, project]);
  const input: Record<string, string> = {
    ...Object.fromEntries(spec.fields.map((f) => [f.key, f.default ?? ""])),
    projectId: project,
    sessionId: initialSessionId ?? "",
    ...values,
  };
  const background = action === "summary" || action === "image";
  const execute = async () => {
    const revision = ++generation.current;
    setPending(true);
    setError(null);
    try {
      if (!serverId) throw "InvalidInput";
      const operation = buildOperation(action, {
        ...input,
        at:
          input.at && !/Z$|[+-]\d\d:\d\d$/.test(input.at)
            ? new Date(input.at).toISOString()
            : input.at,
      });
      if (background) {
        if (!projects.some((p) => p.id === project)) throw "ProjectNotFound";
        const run = await call("background_submit", {
          serverId,
          projectId: project,
          operation: operation as BackgroundOperation,
        });
        // Accepted runs remain tracked even if navigation changed during submission.
        onAccepted(run);
      } else {
        const response = await call("workspace_execute", {
          serverId,
          operation: operation as WorkspaceOperation,
        });
        if (revision === generation.current) setResult(response);
      }
    } catch (e) {
      if (revision === generation.current) setError(e);
    } finally {
      if (revision === generation.current) setPending(false);
    }
  };
  return (
    <section className="page narrow">
      <p className="eyebrow">SERVER OPERATIONS</p>
      <h1>Workspace</h1>
      <form
        className="card"
        onSubmit={(e) => {
          e.preventDefault();
          void execute();
        }}
      >
        <label>
          操作
          <select
            value={action}
            disabled={pending}
            onChange={(e) => {
              generation.current++;
              setAction(e.target.value);
              setValues({});
              setResult(null);
              setError(null);
            }}
          >
            {operations.map((o) => (
              <option key={o.id} value={o.id}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        {(background || action === "workContextUpdate") && (
          <label>
            プロジェクト
            <select
              value={project}
              onChange={(e) => setProject(e.target.value)}
            >
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
        )}
        {spec.fields.map((f) => (
          <label key={f.key}>
            {f.label}
            {f.key === "projectId" ? (
              <select
                value={input.projectId}
                required
                onChange={(e) => setProject(e.target.value)}
              >
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            ) : f.key === "sessionId" ? (
              <select
                value={input.sessionId}
                required
                onChange={(e) =>
                  setValues((v) => ({ ...v, sessionId: e.target.value }))
                }
              >
                <option value="">会話を選択</option>
                {sessions.map((session) => (
                  <option key={session.sessionId} value={session.sessionId}>
                    {session.title}
                  </option>
                ))}
              </select>
            ) : f.key === "id" ||
              (f.key === "name" && collection === "skills") ? (
              <select
                value={input[f.key]}
                required
                onChange={(e) =>
                  setValues((v) => ({ ...v, [f.key]: e.target.value }))
                }
              >
                <option value="">対象を選択</option>
                {targets.map((item) => (
                  <option
                    key={item.id ?? item.title}
                    value={item.id ?? item.title}
                  >
                    {item.title}
                  </option>
                ))}
              </select>
            ) : f.options ? (
              <select
                value={input[f.key]}
                onChange={(e) =>
                  setValues((v) => ({ ...v, [f.key]: e.target.value }))
                }
              >
                {f.options.map((o) => (
                  <option key={o} value={o}>
                    {o || "変更しない"}
                  </option>
                ))}
              </select>
            ) : [
                "url",
                "at",
                "vectorTopK",
                "webTopK",
                "threshold",
                "hours",
                "minutesBefore",
                "confidence",
                "size",
                "displayName",
              ].includes(f.key) ? (
              <input
                type={
                  f.key === "url"
                    ? "url"
                    : f.key === "at"
                      ? "datetime-local"
                      : ["size", "displayName"].includes(f.key)
                        ? "text"
                        : "number"
                }
                step="any"
                value={input[f.key]}
                required={!f.optional}
                onChange={(e) =>
                  setValues((v) => ({ ...v, [f.key]: e.target.value }))
                }
              />
            ) : (
              <textarea
                value={input[f.key]}
                onChange={(e) =>
                  setValues((v) => ({ ...v, [f.key]: e.target.value }))
                }
              />
            )}
          </label>
        ))}
        {targetError && (
          <p role="alert">
            対象を取得できませんでした。操作を選び直して再試行してください。
          </p>
        )}
        {spec.button === "削除" && (
          <p className="notice">この操作はサーバー上のリソースを削除します。</p>
        )}
        <button
          className={spec.button === "削除" ? "danger" : "primary"}
          disabled={pending || !serverId}
        >
          {pending ? "処理中…" : spec.button}
        </button>
      </form>
      {error != null && (
        <p className="notice" role="alert">
          {errorText(error)}
        </p>
      )}
      {result && (
        <section aria-label="操作結果">
          <h2>{result.title}</h2>
          {!result.items.length && (
            <p>完了しました。表示する項目はありません。</p>
          )}
          {result.items.map((item, i) => (
            <article className="card" key={item.id ?? i}>
              <h3>{item.title}</h3>
              {item.id && <small>ID: {item.id}</small>}
              {["feeds", "memories", "reminders", "skills"].includes(
                action,
              ) && (
                <div className="button-row">
                  <button
                    onClick={() => {
                      setValues({
                        [action === "skills" ? "name" : "id"]:
                          item.id ?? item.title,
                      });
                      setAction(
                        (
                          {
                            feeds: "feed",
                            memories: "memory",
                            reminders: "reminder",
                            skills: "skill",
                          } as Record<string, string>
                        )[action],
                      );
                      setResult(null);
                    }}
                  >
                    詳細を開く
                  </button>
                  {action === "feeds" && (
                    <button
                      onClick={() => {
                        setValues({ id: item.id!, displayName: item.title });
                        setAction("updateFeed");
                        setResult(null);
                      }}
                    >
                      編集する
                    </button>
                  )}
                  {action !== "skills" && (
                    <button
                      className="danger"
                      onClick={() => {
                        setValues({ id: item.id! });
                        setAction(
                          (
                            {
                              feeds: "deleteFeed",
                              memories: "deleteMemory",
                              reminders: "deleteReminder",
                            } as Record<string, string>
                          )[action],
                        );
                        setResult(null);
                      }}
                    >
                      削除内容を確認
                    </button>
                  )}
                </div>
              )}
              <dl>
                {item.fields.map(([label, value], j) => (
                  <div key={j}>
                    <dt>{label}</dt>
                    <dd
                      style={{
                        whiteSpace: "pre-wrap",
                        overflowWrap: "anywhere",
                      }}
                    >
                      {value}
                    </dd>
                  </div>
                ))}
              </dl>
            </article>
          ))}
        </section>
      )}
      {runs
        .filter((r) => r.serverId === serverId && !r.conversationId)
        .map((run) => (
          <article className="card" key={run.runId}>
            <h2>{run.prompt}</h2>
            <p>
              {run.status}
              {run.cancelRequested && " · キャンセル要求済み"} ·{" "}
              {run.streamState}
            </p>
            <small>Run {run.runId}</small>
            {onArtifacts && (
              <button
                onClick={() =>
                  onArtifacts({
                    projectId: run.projectId,
                    sessionId: run.sessionId,
                    runId: run.runId,
                  })
                }
              >
                このRunの生成物
              </button>
            )}
            {run.incomplete && (
              <p className="notice">
                イベント履歴の一部が失われました。表示内容は不完全です。
              </p>
            )}
            {activeRuns([run]).length ? (
              <RunTimeline run={run} />
            ) : (
              <div className="answer" style={{ whiteSpace: "pre-wrap" }}>
                {run.assistantText || "結果テキストはありません。"}
              </div>
            )}
            {!run.timeline?.length && activeRuns([run]).length > 0 && (
              <RunActivity run={run} />
            )}
            <button
              onClick={() =>
                void call("run_get", {
                  serverId: run.serverId,
                  runId: run.runId,
                }).catch(setError)
              }
            >
              状態を更新
            </button>
            {activeRuns([run]).length > 0 && (
              <button
                className="danger"
                disabled={run.cancelRequested}
                onClick={() =>
                  void call("run_cancel", {
                    serverId: run.serverId,
                    runId: run.runId,
                  }).catch(setError)
                }
              >
                {run.cancelRequested ? "キャンセル要求済み" : "Stop"}
              </button>
            )}
            {run.error && <p role="alert">{errorText(run.error)}</p>}
          </article>
        ))}
      <p className="muted">
        Profile はイベント統計です。画像の配信 API、Memory の PROJECT/SESSION
        scope、profile 更新・skill ファイル編集はサーバーに公開されていません。
      </p>
    </section>
  );
}
