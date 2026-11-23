import { useState } from "react";
import type { Command } from "../../tauri/commands";
import { SessionPreview } from "./SessionPreview";
import {
  errorText,
  type Project,
  type SessionSummary,
} from "../../entities/models";
import type { HistoryState } from "./pagination";
interface Props {
  serverId?: string | null;
  call?: Command;
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
  serverId,
  call,
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
  const [query, setQuery] = useState("");
  const [archived, setArchived] = useState(false);
  const storageKey = `rei:history:${serverId ?? "local"}`;
  const [preferences, setPreferences] = useState<
    Record<string, { pinned?: boolean; archived?: boolean }>
  >(() => {
    try {
      const value = JSON.parse(localStorage.getItem(storageKey) ?? "{}");
      return value && typeof value === "object" && !Array.isArray(value)
        ? value
        : {};
    } catch {
      return {};
    }
  });
  const update = (
    id: string,
    patch: { pinned?: boolean; archived?: boolean },
  ) => {
    const next = { ...preferences, [id]: { ...preferences[id], ...patch } };
    setPreferences(next);
    try {
      localStorage.setItem(storageKey, JSON.stringify(next));
    } catch {
      /* preferences still work for this launch */
    }
  };
  const visible = state.items
    .filter(
      (session) =>
        Boolean(preferences[session.sessionId]?.archived) === archived &&
        session.title
          .toLocaleLowerCase()
          .includes(query.toLocaleLowerCase().trim()),
    )
    .sort(
      (a, b) =>
        Number(Boolean(preferences[b.sessionId]?.pinned)) -
        Number(Boolean(preferences[a.sessionId]?.pinned)),
    );
  return (
    <section className="page">
      <header className="page-heading">
        <div>
          <p className="eyebrow">YOUR WORKSPACE</p>
          <h1>会話</h1>
          <p className="muted">れいと進める、プロジェクトの続き。</p>
        </div>
        <button className="primary" onClick={onNew}>
          会話を追加
        </button>
      </header>
      <label>
        会話を検索
        <input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="読み込み済みの会話のタイトルを検索"
        />
      </label>
      <label className="checkbox">
        <input
          type="checkbox"
          checked={archived}
          onChange={(event) => setArchived(event.target.checked)}
        />
        アーカイブした会話を表示
      </label>
      <p className="muted">
        ピン留めとアーカイブはこの端末だけに保存します。サーバーの会話は削除しません。
      </p>
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
      {enabled &&
        !state.loadingInitial &&
        visible.length === 0 &&
        state.items.length > 0 && (
          <p role="status">
            一致する会話はありません。未読込の会話は追加で読み込んでください。
          </p>
        )}
      <div className="conversation-grid">
        {visible.map((session) => (
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
            <div className="button-row">
              <button
                onClick={() =>
                  update(session.sessionId, {
                    pinned: !preferences[session.sessionId]?.pinned,
                  })
                }
              >
                {preferences[session.sessionId]?.pinned
                  ? "ピン留めを解除"
                  : "ピン留め"}
              </button>
              <button
                onClick={() =>
                  update(session.sessionId, { archived: !archived })
                }
              >
                {archived ? "アーカイブから戻す" : "アーカイブ"}
              </button>
            </div>
            {call && serverId && (
              <SessionPreview
                call={call}
                serverId={serverId}
                sessionId={session.sessionId}
                updatedAt={session.updatedAt}
              />
            )}
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
