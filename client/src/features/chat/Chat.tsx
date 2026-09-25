import { RunActivity, RunTimeline } from "./RunActivity";
import { timeline } from "../history/timeline";
import { useState } from "react";
import { Avatar } from "../../shared/Avatar";
import {
  activeRuns,
  canSubmit,
  errorText,
  type Conversation,
  type Run,
  type ConversationTurn,
} from "../../entities/models";
import type { HistoryState } from "../history/pagination";
interface Props {
  userAvatar?: string | null;
  conversation: Conversation;
  projectName: string;
  runs: Run[];
  pending: boolean;
  error: string | null;
  onSend: (message: string) => Promise<boolean>;
  onStop: (run: Run) => void;
  onContinue: () => void;
  onRefresh: (run: Run) => void;
  onSubscribe: (run: Run) => void;
  selectedRun?: string;
  history?: HistoryState<ConversationTurn>;
  onMoreHistory?: () => void;
  onRetryHistory?: () => void;
  onRefreshHistory?: () => void;
}
export function Chat({
  userAvatar,
  conversation,
  projectName,
  runs,
  pending,
  error,
  onSend,
  onStop,
  onContinue,
  onRefresh,
  onSubscribe,
  selectedRun,
  history,
  onMoreHistory,
  onRetryHistory,
  onRefreshHistory,
}: Props) {
  const [message, setMessage] = useState("");
  const submitDisabled =
    !canSubmit(conversation.localId, message, runs, pending) ||
    error === "SessionNotFound" ||
    history?.error === "SessionNotFound";
  return (
    <section className="chat-page">
      <header className="page-heading">
        <div>
          <p className="eyebrow">CONVERSATION</p>
          <h1>{conversation.title}</h1>
          {conversation.sessionId && (
            <>
              <small>
                Updated:{" "}
                {new Date(conversation.lastAccessedAt).toLocaleString()}
              </small>
              <details className="session-identity">
                <summary>Session ID</summary>
                <code>{conversation.sessionId}</code>
              </details>
            </>
          )}
        </div>
        <span className="pill">
          {projectName}
          {conversation.sessionId ? " 🔒" : ""}
        </span>
      </header>
      <div className="transcript">
        {conversation.sessionId && onRefreshHistory && (
          <button
            onClick={onRefreshHistory}
            disabled={history?.loadingInitial || history?.refreshing}
          >
            履歴を更新
          </button>
        )}
        {history?.loadingInitial && (
          <p role="status">メッセージを読み込んでいます…</p>
        )}
        {history?.error && (
          <div role="alert" className="notice">
            {errorText(history.error)}{" "}
            {history.stale && "最後に取得した履歴です（未同期）。"}
            <button onClick={onRetryHistory}>履歴を再試行</button>
          </div>
        )}
        {!runs.length &&
          !history?.items.length &&
          !history?.loadingInitial &&
          !history?.error && (
            <div className="empty">
              <Avatar role="assistant" className="welcome-avatar" />
              <h2>れいと、次の一歩へ。</h2>
              <p>コードの調査や作業を依頼してください。</p>
              <small>
                {conversation.sessionId
                  ? "この会話にはまだメッセージがありません。"
                  : "最初の送信で新しい会話を作成します。"}
              </small>
            </div>
          )}
        {timeline(history?.items ?? [], runs).map(({ turn, run }) =>
          run ? (
            <article
              key={run.runId}
              id={`run-${run.runId}`}
              className={`turn ${selectedRun === run.runId ? "highlight" : ""}`}
            >
              <div className="message user">
                <span className="message-label">
                  <Avatar role="user" src={userAvatar} />
                  YOU
                </span>
                <p>{run.prompt}</p>
              </div>
              <div className="message assistant">
                <div className="message-heading">
                  <span className="message-label">
                    <Avatar role="assistant" />
                    REI
                  </span>
                  <span className={`status ${run.status.toLowerCase()}`}>
                    {run.status}
                  </span>
                  <span className="muted">{run.streamState}</span>
                </div>
                {run.incomplete && (
                  <p className="notice">
                    イベント履歴の一部を取得できませんでした。表示内容は不完全です。
                  </p>
                )}
                {run.timeline?.length ? (
                  <>
                    <RunTimeline run={run} />
                    {run.incomplete &&
                      !activeRuns([run]).length &&
                      turn?.assistantMessage &&
                      turn.assistantMessage !== run.assistantText && (
                        <div className="answer">
                          <small>保存済みの最終回答</small>
                          <p>{turn.assistantMessage}</p>
                        </div>
                      )}
                  </>
                ) : (
                  <>
                    <div className="answer">
                      {(turn?.assistantMessage != null &&
                      !activeRuns([run]).length
                        ? turn.assistantMessage
                        : run.assistantText) ||
                        (!activeRuns([run]).length
                          ? "回答テキストはありません。"
                          : "れいが作業しています…")}
                    </div>
                    <RunActivity run={run} />
                  </>
                )}
                {!!run.workingSet.length && (
                  <details className="activity">
                    <summary>Working set · {run.workingSet.length}</summary>
                    {run.workingSet.map((item) => (
                      <p key={item.id} className="file-path">
                        {item.path || item.identifier}{" "}
                        <small>{item.kind}</small>
                      </p>
                    ))}
                  </details>
                )}
                {run.failure && <p className="notice">Run が失敗しました。</p>}
                {run.error && <p className="muted">{errorText(run.error)}</p>}
                <div className="run-actions">
                  <small>Run {run.runId.slice(0, 8)}</small>
                  <button className="quiet" onClick={() => onRefresh(run)}>
                    状態を更新
                  </button>
                  {activeRuns([run]).length > 0 && (
                    <>
                      <button className="danger" onClick={() => onStop(run)}>
                        Stop
                      </button>
                      {run.streamState === "CLOSED" && (
                        <button onClick={() => onSubscribe(run)}>再接続</button>
                      )}
                    </>
                  )}
                </div>
              </div>
            </article>
          ) : turn ? (
            <article className="turn" key={turn.runId}>
              <time className="muted" dateTime={turn.createdAt}>
                {new Date(turn.createdAt).toLocaleString()}
              </time>
              {turn.userMessage && (
                <div className="message user">
                  <span className="message-label">
                    <Avatar role="user" src={userAvatar} />
                    YOU
                  </span>
                  <p>{turn.userMessage}</p>
                </div>
              )}
              <div className="message assistant">
                <span className="message-label">
                  <Avatar role="assistant" />
                  REI
                </span>
                <div className="answer">
                  {turn.assistantMessage ?? "応答はまだ記録されていません。"}
                </div>
              </div>
            </article>
          ) : null,
        )}
        {history?.nextCursor && (
          <button
            className="load-more"
            onClick={onMoreHistory}
            disabled={history.loadingMore || history.refreshing}
          >
            {history.loadingMore ? "読み込み中…" : "次のメッセージを読み込む"}
          </button>
        )}
        {history?.error === "SessionNotFound" && (
          <button onClick={onContinue}>新しい会話として続ける</button>
        )}
      </div>
      <div className="composer-area">
        {error && (
          <div className="notice" role="alert">
            {errorText(error)}
            {error === "SessionNotFound" && (
              <button onClick={onContinue}>新しい会話として続ける</button>
            )}
          </div>
        )}
        <form
          className="composer"
          onSubmit={async (e) => {
            e.preventDefault();
            if (submitDisabled) return;
            if (await onSend(message)) setMessage("");
          }}
        >
          <label className="sr-only" htmlFor="message">
            メッセージ
          </label>
          <textarea
            id="message"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            onKeyDown={(e) => {
              if (
                e.key !== "Enter" ||
                e.shiftKey ||
                e.nativeEvent.isComposing ||
                e.nativeEvent.keyCode === 229
              )
                return;
              e.preventDefault();
              if (!e.repeat && !submitDisabled)
                e.currentTarget.form?.requestSubmit();
            }}
            placeholder="れいに依頼する…"
            rows={3}
          />
          <div className="composer-footer">
            <small>
              {conversation.sessionId
                ? "同じセッションで続けます"
                : "最初の送信でセッションを作成します"}
            </small>
            <button className="primary" disabled={submitDisabled}>
              {pending ? "送信中…" : "Send ↗"}
            </button>
          </div>
        </form>
      </div>
    </section>
  );
}
