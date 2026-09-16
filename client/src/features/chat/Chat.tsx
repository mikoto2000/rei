import { useState } from "react";
import {
  activeRuns,
  canSubmit,
  errorText,
  type Conversation,
  type Run,
} from "../../entities/models";
interface Props {
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
}
export function Chat({
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
}: Props) {
  const [message, setMessage] = useState("");
  return (
    <section className="chat-page">
      <header className="page-heading">
        <div>
          <p className="eyebrow">CONVERSATION</p>
          <h1>{conversation.title}</h1>
        </div>
        <span className="pill">
          {projectName}
          {conversation.sessionId ? " 🔒" : ""}
        </span>
      </header>
      <div className="transcript">
        {!runs.length && (
          <div className="empty">
            <div className="rei-mark">r.</div>
            <h2>れいと、次の一歩へ。</h2>
            <p>コードの調査や作業を依頼してください。</p>
            <small>
              以前の会話本文は保存されません。session は継続できます。
            </small>
          </div>
        )}
        {runs.map((run) => (
          <article
            key={run.runId}
            id={`run-${run.runId}`}
            className={`turn ${selectedRun === run.runId ? "highlight" : ""}`}
          >
            <div className="message user">
              <span className="message-label">YOU</span>
              <p>{run.prompt}</p>
            </div>
            <div className="message assistant">
              <div className="message-heading">
                <span className="message-label">REI</span>
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
              <div className="answer">
                {run.assistantText ||
                  (!activeRuns([run]).length
                    ? "回答テキストはありません。"
                    : "れいが作業しています…")}
              </div>
              {!!run.tools.length && (
                <details className="activity" open>
                  <summary>Tool activity · {run.tools.length}</summary>
                  {run.tools.map((tool) => (
                    <div className="tool" key={tool.id}>
                      <span>
                        {tool.status === "COMPLETED"
                          ? "✓"
                          : tool.status === "FAILED"
                            ? "!"
                            : "↻"}
                      </span>
                      <div>
                        <strong>{tool.name}</strong>
                        <p>{tool.summary}</p>
                      </div>
                      <small>{tool.status}</small>
                    </div>
                  ))}
                </details>
              )}
              {!!run.workingSet.length && (
                <details className="activity">
                  <summary>Working set · {run.workingSet.length}</summary>
                  {run.workingSet.map((item) => (
                    <p key={item.id} className="file-path">
                      {item.path || item.identifier} <small>{item.kind}</small>
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
        ))}
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
            placeholder="れいに依頼する…"
            rows={3}
          />
          <div className="composer-footer">
            <small>
              {conversation.sessionId
                ? "同じセッションで続けます"
                : "最初の送信でセッションを作成します"}
            </small>
            <button
              className="primary"
              disabled={
                !canSubmit(conversation.localId, message, runs, pending)
              }
            >
              {pending ? "送信中…" : "Send ↗"}
            </button>
          </div>
        </form>
      </div>
    </section>
  );
}
