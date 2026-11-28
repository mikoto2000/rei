import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import type { WorkspaceResult } from "../../entities/workspace";
import { errorText } from "../../entities/models";

type Item = WorkspaceResult["items"][number];
const field = (item: Item, key: string) =>
  item.fields.find(([name]) => name === key)?.[1] ?? "";
const statuses: Record<string, string> = {
  RUNNING: "検討中",
  POLICY_SKIPPED: "制約により見送り",
  SKIPPED: "見送り",
  GENERATED: "投稿案を保存",
  FAILED: "生成失敗",
  ABORTED: "中断",
};

export function AutonomousHistory({
  call,
  serverId,
  onDraft,
  disabled = false,
}: {
  call: Command;
  serverId: string | null;
  onDraft: (id: string) => void;
  disabled?: boolean;
}) {
  const [view, setView] = useState<{
    owner: string;
    published: Item[];
    attempts: Item[];
  } | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const generation = useRef(0);
  useEffect(() => {
    const epoch = ++generation.current;
    setView(null);
    setError(null);
    setPending(Boolean(serverId));
    if (serverId) {
      void Promise.all([
        call("workspace_execute", {
          serverId,
          operation: { operation: "autonomousPublished", limit: 20 },
        }),
        call("workspace_execute", {
          serverId,
          operation: { operation: "autonomousHistory", limit: 20 },
        }),
      ])
        .then(([posts, attempts]) => {
          if (epoch === generation.current)
            setView({
              owner: serverId,
              published: posts.items.filter(
                (item) =>
                  field(item, "Type") === "AutonomousDraft" &&
                  field(item, "Status") === "PUBLISHED",
              ),
              attempts: attempts.items.filter(
                (item) => field(item, "Type") === "AutonomousHistory",
              ),
            });
        })
        .catch((failure) => {
          if (epoch === generation.current) setError(errorText(failure));
        })
        .finally(() => {
          if (epoch === generation.current) setPending(false);
        });
    }
    return () => {
      generation.current++;
    };
  }, [call, serverId, refresh]);
  const visible = view?.owner === serverId ? view : null;
  return (
    <section aria-label="投稿・生成履歴" className="card">
      <h2>投稿・生成履歴</h2>
      <p>
        このサーバーの自発投稿と生成の検討を、それぞれ直近 20
        件表示します。履歴の参照では公開や再送を行いません。
      </p>
      {!serverId && <p>サーバーを選択してください。</p>}
      <button
        disabled={!serverId || pending}
        onClick={() => setRefresh((value) => value + 1)}
      >
        履歴を更新
      </button>
      {pending && <p role="status">履歴を取得中です。</p>}
      {error && <p role="alert">{error}</p>}
      {visible && (
        <>
          <h3>公開済み投稿</h3>
          {visible.published.length === 0 && (
            <p>公開を確認できた投稿はありません。</p>
          )}
          {visible.published.map((post) => (
            <article className="card" key={post.id}>
              <p>
                {field(post, "Account")} · 最終確認: {field(post, "UpdatedAt")}
              </p>
              <pre className="autonomous-body">{field(post, "Text")}</pre>
              <dl>
                {[
                  ["DID", "DID"],
                  ["URI", "URI"],
                  ["CID", "CID"],
                ].map(([label, key]) => (
                  <div key={key}>
                    <dt>{label}</dt>
                    <dd>{field(post, key)}</dd>
                  </div>
                ))}
              </dl>
            </article>
          ))}
          <h3>生成の検討履歴</h3>
          {visible.attempts.length === 0 && <p>検討履歴はありません。</p>}
          {visible.attempts.map((attempt) => (
            <article className="card" key={attempt.id}>
              <p>
                {statuses[field(attempt, "Status")] ?? field(attempt, "Status")}{" "}
                · {field(attempt, "Trigger") === "SCHEDULED" ? "定期" : "手動"}{" "}
                · {field(attempt, "Kind") === "REGENERATE" ? "再生成" : "新規"}
              </p>
              <p>{field(attempt, "CreatedAt")}</p>
              <p>
                {field(attempt, "Reason") === "duplicate_publication"
                  ? "公開済み・公開中・結果不明の投稿と内容が重複しています。"
                  : field(attempt, "Reason")}
              </p>
              {field(attempt, "DraftId") && (
                <button
                  disabled={disabled || pending}
                  aria-label={`投稿案を開く ${field(attempt, "DraftId")}`}
                  onClick={() => onDraft(field(attempt, "DraftId"))}
                >
                  投稿案を開く
                </button>
              )}
            </article>
          ))}
        </>
      )}
    </section>
  );
}
