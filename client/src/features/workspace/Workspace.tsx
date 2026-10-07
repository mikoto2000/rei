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
}: {
  call: Command;
  serverId: string | null;
  projects: Project[];
  runs: Run[];
  onAccepted: (run: Run) => void;
  onArtifacts?: (selection: ArtifactSelection) => void;
}) {
  const [action, setAction] = useState("feeds");
  const [values, setValues] = useState<Record<string, string>>({});
  const [project, setProject] = useState(projects[0]?.id ?? "");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<WorkspaceResult | null>(null);
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setResult(null);
    setError(null);
    setPending(false);
    setProject(projects[0]?.id ?? "");
    return () => {
      generation.current++;
    };
  }, [serverId, projects]);
  const spec = operations.find((s) => s.id === action)!;
  const input = {
    ...Object.fromEntries(spec.fields.map((f) => [f.key, f.default ?? ""])),
    ...values,
  };
  const background = action === "summary" || action === "image";
  const execute = async () => {
    const revision = ++generation.current;
    setPending(true);
    setError(null);
    try {
      if (!serverId) throw "InvalidInput";
      const operation = buildOperation(action, input);
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
        {background && (
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
            {f.options ? (
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
