import {
  errorText,
  type Project,
  type SessionSummary,
} from "../../entities/models";
import type { HistoryState } from "./pagination";
interface Props {
  state: HistoryState<SessionSummary>;
  projects: Project[];
  projectId: string | null;
  onProject: (id: string | null) => void;
  onMore: () => void;
  onRefresh: () => void;
  onRetry: () => void;
  onOpen: (id: string) => void;
  onNew: () => void;
  enabled: boolean;
}
export function SessionList({
  state,
  projects,
  projectId,
  onProject,
  onMore,
  onRefresh,
  onRetry,
  onOpen,
  onNew,
  enabled,
}: Props) {
  return (
    <section className="page">
      <header className="page-heading">
        <div>
          <p className="eyebrow">YOUR WORKSPACE</p>
          <h1>Conversations</h1>
          <p className="muted">れいと進める、プロジェクトの続き。</p>
        </div>
        <button className="primary" onClick={onNew}>
          会話を追加
        </button>
      </header>
      <div className="history-toolbar">
        <label>
          会話のプロジェクト
          <select
            aria-label="会話のプロジェクト"
            value={projectId ?? ""}
            onChange={(e) => onProject(e.target.value || null)}
            disabled={!enabled}
          >
            <option value="">All Projects</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} · {p.path}
              </option>
            ))}
          </select>
        </label>
        <button
          onClick={onRefresh}
          disabled={!enabled || state.loadingInitial || state.refreshing}
          aria-label="会話一覧を更新"
        >
          {state.refreshing ? "更新中…" : "更新"}
        </button>
      </div>
      {!enabled ? (
        <p>サーバーを選択して Vault を解錠してください。</p>
      ) : state.loadingInitial ? (
        <p role="status">会話を読み込んでいます…</p>
      ) : null}
      {state.error && (
        <div className="notice" role="alert">
          {errorText(state.error)}{" "}
          {state.stale && <span>最後に取得した一覧です（未同期）。</span>}
          <button onClick={onRetry}>再試行</button>
        </div>
      )}
      {enabled && state.status === "EMPTY" && (
        <div className="empty">
          <div className="rei-mark">r.</div>
          <h2>まだ会話がありません</h2>
          <button onClick={onNew}>新しい会話を開始</button>
        </div>
      )}
      <div className="conversation-grid">
        {state.items.map((session) => (
          <article className="conversation-card" key={session.sessionId}>
            <button onClick={() => onOpen(session.sessionId)}>
              <span className="eyebrow">
                {projects.find((p) => p.id === session.projectId)?.name ??
                  session.projectId}
              </span>
              <h2>{session.title}</h2>
              <time dateTime={session.updatedAt}>
                {new Date(session.updatedAt).toLocaleString()}
              </time>
            </button>
          </article>
        ))}
      </div>
      {state.nextCursor !== null && (
        <button
          className="load-more"
          onClick={onMore}
          disabled={state.loadingMore || state.refreshing}
        >
          {state.loadingMore ? "読み込み中…" : "会話をもっと読み込む"}
        </button>
      )}
    </section>
  );
}
