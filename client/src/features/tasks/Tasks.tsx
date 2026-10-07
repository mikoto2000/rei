import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import { errorText, type Project } from "../../entities/models";
import type { ManagedTask, TaskAction } from "../../entities/tasks";
export function Tasks({
  call,
  serverId,
  projects,
}: {
  call: Command;
  serverId: string | null;
  projects: Project[];
}) {
  const [project, setProject] = useState("");
  const [session, setSession] = useState("");
  const [sessionDraft, setSessionDraft] = useState("");
  const [items, setItems] = useState<ManagedTask[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const [request, setRequest] = useState("");
  const generation = useRef(0);
  const load = async (
    next: string | null = null,
    token = generation.current,
  ) => {
    if (!serverId) return;
    setBusy(true);
    setError(null);
    try {
      const page = await call("tasks_list", {
        serverId,
        projectId: project || null,
        sessionId: session || null,
        limit: 50,
        cursor: next,
      });
      if (token !== generation.current) return;
      setItems((old) =>
        next
          ? [
              ...old,
              ...page.items.filter((t) => !old.some((x) => x.id === t.id)),
            ]
          : page.items,
      );
      setCursor(page.nextCursor);
    } catch (e) {
      if (token === generation.current) setError(errorText(e));
    } finally {
      if (token === generation.current) setBusy(false);
    }
  };
  useEffect(() => {
    const token = ++generation.current;
    setItems([]);
    setCursor(null);
    setBusy(false);
    setError(null);
    void load(null, token);
    return () => {
      generation.current++;
    };
  }, [serverId, project, session, call]);
  const control = async (task: ManagedTask, action: TaskAction) => {
    if (!serverId || busy) return;
    const token = generation.current;
    setBusy(true);
    setError(null);
    try {
      const updated = await call("task_control", {
        serverId,
        projectId: task.projectId,
        sessionId: task.sessionId,
        taskId: task.id,
        expectedRunId: task.runId,
        expectedRevision: task.revision,
        action,
        ...(action === "input" ? { message } : {}),
      });
      if (token === generation.current) {
        setItems((old) => old.map((t) => (t.id === updated.id ? updated : t)));
        if (action === "input") setMessage("");
      }
    } catch (e) {
      if (token === generation.current) setError(errorText(e));
    } finally {
      if (token === generation.current) setBusy(false);
    }
  };
  const submit = async () => {
    if (!serverId || !project || busy || !request.trim()) return;
    const token = generation.current;
    setBusy(true);
    setError(null);
    try {
      const accepted = await call("task_submit", {
        serverId,
        projectId: project,
        message: request,
      });
      if (token === generation.current) {
        setItems((old) => [
          accepted,
          ...old.filter((t) => t.id !== accepted.id),
        ]);
        setRequest("");
      }
    } catch (e) {
      if (token === generation.current) setError(errorText(e));
    } finally {
      if (token === generation.current) setBusy(false);
    }
  };
  return (
    <section className="page narrow task-manager">
      <h2>Task Manager</h2>
      <p>
        サーバーに保存された実行状況です。再開は選択したTaskに対して明示的に行います。
      </p>
      {!serverId ? (
        <p>サーバーを選択し、認証を解除してください。</p>
      ) : (
        <>
          <label>
            Project
            <select
              aria-label="TaskのProject"
              value={project}
              disabled={busy}
              onChange={(e) => setProject(e.target.value)}
            >
              <option value="">すべて</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <button disabled={busy} onClick={() => void load()}>
            更新
          </button>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              setSession(sessionDraft.trim());
            }}
          >
            <label>
              TaskのSession
              <input
                value={sessionDraft}
                maxLength={256}
                onChange={(e) => setSessionDraft(e.target.value)}
              />
            </label>
            <button disabled={busy}>絞り込む</button>
          </form>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void submit();
            }}
          >
            <label>
              新しいTaskの依頼
              <textarea
                value={request}
                maxLength={16384}
                onChange={(e) => setRequest(e.target.value)}
              />
            </label>
            <button disabled={busy || !project || !request.trim()}>
              Taskを開始
            </button>
            <p>
              開始するProjectを選択してください。新しいSessionで実行します。
            </p>
          </form>
          {error && (
            <p role="alert">{error}。状態を更新してから操作してください。</p>
          )}
          {items.map((t) => (
            <article className="card" key={t.id}>
              <h3>{t.id}</h3>
              <p>{t.status}</p>
              <p>
                {projects.find((p) => p.id === t.projectId)?.name ??
                  t.projectId}{" "}
                · {t.kind} {t.mode}
              </p>
              {t.sessionId && <p>Session: {t.sessionId}</p>}
              {t.runId && <p>Run: {t.runId}</p>}
              {t.waitingReason && <p>{t.waitingReason}</p>}
              {t.startedAt && <p>開始: {t.startedAt}</p>}
              {t.updatedAt && <p>最終更新: {t.updatedAt}</p>}
              {t.goalId && <p>Goal: {t.goalId}</p>}
              {t.schedulerId && <p>Scheduler: {t.schedulerId}</p>}
              {t.dependencyIds.length > 0 && (
                <p>Dependency: {t.dependencyIds.join(", ")}</p>
              )}
              {t.errorSummary && <p>{t.errorSummary}</p>}
              {t.progress && (
                <p>
                  記録された進捗: {t.progress.completed} / {t.progress.total}
                </p>
              )}
              {t.parentId && <p>親Task: {t.parentId}</p>}
              {t.childIds.length > 0 && <p>子Task: {t.childIds.join(", ")}</p>}
              {t.results.map((r) => (
                <p key={`${r.kind}:${r.id}`}>
                  {r.kind}: {r.id}
                </p>
              ))}
              {t.cancelSupported && (
                <button
                  disabled={busy}
                  onClick={() => void control(t, "cancel")}
                >
                  中止
                </button>
              )}
              {t.suspendSupported && (
                <button
                  disabled={busy}
                  onClick={() => void control(t, "suspend")}
                >
                  中断して状態を保持
                </button>
              )}
              {t.resumeSupported && (
                <button
                  disabled={busy}
                  onClick={() => void control(t, "resume")}
                >
                  再開
                </button>
              )}
              {t.inputSupported && (
                <div>
                  <label>
                    追加指示
                    <textarea
                      value={message}
                      maxLength={16384}
                      onChange={(e) => setMessage(e.target.value)}
                    />
                  </label>
                  <button
                    disabled={busy || !message.trim()}
                    onClick={() => void control(t, "input")}
                  >
                    追加指示を送信
                  </button>
                </div>
              )}
            </article>
          ))}
          {!busy && !items.length && <p>Taskはありません。</p>}
          {cursor && (
            <button disabled={busy} onClick={() => void load(cursor)}>
              さらに読み込む
            </button>
          )}
        </>
      )}
    </section>
  );
}
