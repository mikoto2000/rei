import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import { errorText } from "../../entities/models";
import type {
  WorkspaceOperation,
  WorkspaceResult,
} from "../../entities/workspace";

type Item = WorkspaceResult["items"][number];
const field = (item: Item, key: string) =>
  item.fields.find(([name]) => name === key)?.[1] ?? "";
type View = {
  owner: string;
  drafts: Item[];
  sources: Item[];
  draft: Item | null;
  source: Item | null;
};

export function AutonomousDrafts({
  call,
  serverId,
  initialDraftId,
}: {
  call: Command;
  serverId: string | null;
  initialDraftId?: string;
}) {
  const [view, setView] = useState<View | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [topics, setTopics] = useState(false);
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState("");
  const [reason, setReason] = useState("");
  const [sourceText, setSourceText] = useState("");
  const [newText, setNewText] = useState("");
  const [allowed, setAllowed] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const generation = useRef(0);
  const busy = useRef(false);
  useEffect(() => {
    const epoch = ++generation.current;
    setView(null);
    setError(null);
    setNotice(null);
    setTopics(false);
    setEditing(false);
    setText("");
    setReason("");
    setSourceText("");
    setNewText("");
    setAllowed(false);
    setConfirming(false);
    setNeedsRefresh(false);
    busy.current = false;
    setPending(Boolean(serverId));
    if (serverId) {
      busy.current = true;
      void Promise.all([
        call("workspace_execute", {
          serverId,
          operation: { operation: "autonomousDrafts", limit: 100 },
        }),
        call("workspace_execute", {
          serverId,
          operation: { operation: "autonomousSources", limit: 100 },
        }),
        initialDraftId
          ? call("workspace_execute", {
              serverId,
              operation: { operation: "autonomousDraft", id: initialDraftId },
            })
          : Promise.resolve(null),
      ])
        .then(([drafts, sources, selected]) => {
          if (epoch === generation.current)
            setView({
              owner: serverId,
              drafts: drafts.items,
              sources: sources.items,
              draft:
                selected?.items.find(
                  (item) => field(item, "Type") === "AutonomousDraft",
                ) ?? null,
              source: null,
            });
        })
        .catch((failure) => {
          if (epoch === generation.current) setError(errorText(failure));
        })
        .finally(() => {
          if (epoch === generation.current) {
            busy.current = false;
            setPending(false);
          }
        });
    }
    return () => {
      generation.current++;
    };
  }, [call, serverId, initialDraftId, refresh]);
  const visible = view?.owner === serverId ? view : null;
  const run = async (operation: WorkspaceOperation) => {
    if (!serverId || !visible || busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    setNotice(null);
    setConfirming(false);
    const epoch = generation.current;
    try {
      const result = await call("workspace_execute", { serverId, operation });
      if (epoch !== generation.current) return;
      const draft = result.items.find(
        (item) => field(item, "Type") === "AutonomousDraft",
      );
      const source = result.items.find(
        (item) => field(item, "Type") === "AutonomousSource",
      );
      const outcome = result.items.find(
        (item) => field(item, "Type") === "AutonomousGeneration",
      );
      setView((current) =>
        current?.owner !== serverId
          ? current
          : {
              ...current,
              drafts: draft
                ? [
                    draft,
                    ...current.drafts.filter((item) => item.id !== draft.id),
                  ]
                : current.drafts,
              sources: source
                ? [
                    source,
                    ...current.sources.filter((item) => item.id !== source.id),
                  ]
                : current.sources,
              draft: draft ?? current.draft,
              source: source ?? current.source,
            },
      );
      if (draft) {
        setNeedsRefresh(false);
        setEditing(false);
        setText("");
        setReason("");
      }
      if (source) setSourceText(field(source, "Text"));
      if (operation.operation === "autonomousAddSource") {
        setNewText("");
        setAllowed(false);
      }
      if (outcome) {
        const message = `${field(outcome, "GenerationStatus")}: ${field(outcome, "GenerationReason")}`;
        if (field(outcome, "GenerationStatus") === "FAILED") setError(message);
        else setNotice(message);
      }
    } catch (failure) {
      if (
        epoch === generation.current &&
        operation.operation === "autonomousPublish"
      )
        setNeedsRefresh(true);
      if (epoch === generation.current)
        setError(
          `${errorText(failure)}。競合や通信切断の場合は更新して現在の内容を確認してください。`,
        );
    } finally {
      if (epoch === generation.current) {
        busy.current = false;
        setPending(false);
      }
    }
  };
  const draft = visible?.draft;
  const source = visible?.source;
  const revision = draft ? Number(field(draft, "Revision")) : 0;
  const sourceRevision = source ? Number(field(source, "Revision")) : 0;
  const mutable =
    draft && field(draft, "Status") === "PENDING_REVIEW" && !needsRefresh;
  const publishable =
    draft &&
    draft.id &&
    Number.isInteger(revision) &&
    revision > 0 &&
    ["PENDING_REVIEW", "FAILED"].includes(field(draft, "Status"));
  const sourceDirty = source && sourceText !== field(source, "Text");
  return (
    <section className="page narrow autonomous-pane" aria-label="自発投稿案">
      <h1>自発投稿案</h1>
      <p>
        投稿案を確認し、編集・再生成・見送りができます。生成した本文はドラフトに保存されます。
      </p>
      {!serverId && <p>サーバーを選択し、認証を解除してください。</p>}
      <div className="autonomous-actions">
        <button
          disabled={!serverId || pending}
          onClick={() => setRefresh((value) => value + 1)}
        >
          更新
        </button>
        <button
          disabled={!visible || pending}
          onClick={() => void run({ operation: "autonomousGenerate" })}
        >
          投稿案を生成
        </button>
        <button
          disabled={!visible || pending}
          onClick={() => setTopics((value) => !value)}
        >
          公開用の話題を管理
        </button>
      </div>
      {pending && <p role="status">処理中…</p>}
      {error && <p role="alert">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      <h2>投稿案</h2>
      {visible?.drafts.length === 0 && <p>投稿案はありません。</p>}
      {visible?.drafts.map((item) => (
        <article className="card" key={item.id}>
          <p>
            {field(item, "Account")} · {field(item, "Status")} · 改訂{" "}
            {field(item, "Revision")}
          </p>
          <p className="autonomous-preview">{field(item, "Text")}</p>
          <p>
            作成: {field(item, "CreatedAt")} / 有効期限:{" "}
            {field(item, "ExpiresAt")}
          </p>
          <button
            aria-label={`投稿案を確認 ${item.id}`}
            disabled={pending}
            onClick={() =>
              item.id && void run({ operation: "autonomousDraft", id: item.id })
            }
          >
            全文を確認
          </button>
        </article>
      ))}
      {draft && (
        <section className="card" aria-label="投稿案の詳細">
          <h2>投稿案の詳細</h2>
          <dl>
            {[
              ["アカウント", "Account"],
              ["DID", "DID"],
              ["状態", "Status"],
              ["改訂", "Revision"],
              ["投稿理由", "Reason"],
              ["参照資料 ID", "SourceIds"],
              ["契機", "Trigger"],
              ["作成日時", "CreatedAt"],
              ["更新日時", "UpdatedAt"],
              ["有効期限", "ExpiresAt"],
              ["再生成試行回数", "RegenerationAttempts"],
              ["最終再生成試行", "LastRegenerationAttemptAt"],
              ["見送り理由（非公開）", "RejectionReason"],
              ["結果", "Result"],
              ["失敗", "Failure"],
              ["URI", "URI"],
              ["CID", "CID"],
            ].map(([label, key]) => (
              <div key={key}>
                <dt>{label}</dt>
                <dd>{field(draft, key) || "—"}</dd>
              </div>
            ))}
          </dl>
          <pre className="autonomous-body" aria-label="投稿本文の全文">
            {field(draft, "Text")}
          </pre>
          {mutable && (
            <>
              {editing ? (
                <div>
                  <label>
                    投稿本文
                    <textarea
                      aria-label="投稿本文"
                      value={text}
                      disabled={pending}
                      onChange={(event) => setText(event.target.value)}
                    />
                  </label>
                  <button
                    disabled={pending || !text.trim()}
                    onClick={() =>
                      draft.id &&
                      void run({
                        operation: "autonomousEdit",
                        id: draft.id,
                        expectedRevision: revision,
                        text,
                      })
                    }
                  >
                    編集を保存
                  </button>
                  <button disabled={pending} onClick={() => setEditing(false)}>
                    編集を取り消す
                  </button>
                </div>
              ) : (
                <button
                  disabled={pending}
                  onClick={() => {
                    setConfirming(false);
                    setText(field(draft, "Text"));
                    setEditing(true);
                  }}
                >
                  本文を編集
                </button>
              )}
              <button
                disabled={pending || editing}
                onClick={() =>
                  draft.id &&
                  void run({
                    operation: "autonomousRegenerate",
                    id: draft.id,
                    expectedRevision: revision,
                  })
                }
              >
                再生成
              </button>
              <p>
                再生成には利用を許可した話題だけを使います。失敗・見送りも試行回数に含まれます。
              </p>
              <label>
                見送り理由（非公開・任意）
                <textarea
                  aria-label="見送り理由（非公開・任意）"
                  maxLength={2048}
                  disabled={pending}
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                />
              </label>
              <button
                disabled={pending || editing}
                onClick={() =>
                  draft.id &&
                  void run({
                    operation: "autonomousReject",
                    id: draft.id,
                    expectedRevision: revision,
                    reason,
                  })
                }
              >
                見送る
              </button>
            </>
          )}
          {publishable && (
            <button
              disabled={pending || editing || needsRefresh}
              onClick={() => setConfirming(true)}
            >
              {field(draft, "Status") === "FAILED"
                ? "同じ内容で再試行を確認"
                : "公開内容を確認"}
            </button>
          )}
          {confirming && publishable && !editing && !needsRefresh && (
            <section role="dialog" aria-label="投稿の公開確認" className="card">
              <h3>投稿の公開確認</h3>
              <p>投稿先: {field(draft, "Account")}</p>
              <p>DID: {field(draft, "DID")}</p>
              <p>
                改訂: {revision} · 有効期限: {field(draft, "ExpiresAt")}
              </p>
              <pre className="autonomous-body" aria-label="公開する本文の全文">
                {field(draft, "Text")}
              </pre>
              <p>
                この保存済みの本文を公開します。編集・再生成した場合は、新しい版を改めて確認してください。
              </p>
              <button
                disabled={pending}
                onClick={() =>
                  draft.id &&
                  void run({
                    operation: "autonomousPublish",
                    id: draft.id,
                    expectedRevision: revision,
                  })
                }
              >
                この内容を公開する
              </button>
              <button disabled={pending} onClick={() => setConfirming(false)}>
                公開を取り消す
              </button>
            </section>
          )}
          {needsRefresh && (
            <p role="status">
              公開結果を確認できませんでした。再操作する前に更新して現在の状態を確認してください。
            </p>
          )}
          {field(draft, "Status") === "UNKNOWN" && (
            <>
              <p>
                公開結果が不明です。再送せず、保存した投稿キーと本文で結果を照合します。
              </p>
              <button
                disabled={pending}
                onClick={() =>
                  draft.id &&
                  void run({
                    operation: "autonomousReconcile",
                    id: draft.id,
                    expectedRevision: revision,
                  })
                }
              >
                公開結果を照合
              </button>
            </>
          )}
          <button
            disabled={pending}
            onClick={() => {
              setView((current) => current && { ...current, draft: null });
              setEditing(false);
              setConfirming(false);
              setReason("");
            }}
          >
            保留して閉じる
          </button>
        </section>
      )}
      {topics && visible && (
        <section aria-label="公開用の話題">
          <h2>公開用の話題</h2>
          <p>
            利用を許可した話題の本文だけが生成に渡されます。本文の変更後は改めて利用許可が必要です。
          </p>
          {visible.sources.map((item) => (
            <article className="card" key={item.id}>
              <p className="autonomous-preview">{field(item, "Text")}</p>
              <p>
                改訂 {field(item, "Revision")} · 利用許可:{" "}
                {field(item, "PublicUseAllowed")}
              </p>
              <button
                aria-label={`話題を確認 ${item.id}`}
                disabled={pending}
                onClick={() =>
                  item.id &&
                  void run({ operation: "autonomousSource", id: item.id })
                }
              >
                話題を確認
              </button>
            </article>
          ))}
          {source && (
            <section className="card" aria-label="話題の詳細">
              <h3>話題の詳細</h3>
              <p>
                {source.id} · 改訂 {sourceRevision}
              </p>
              <label>
                話題の本文
                <textarea
                  aria-label="話題の本文"
                  value={sourceText}
                  disabled={pending}
                  onChange={(event) => setSourceText(event.target.value)}
                />
              </label>
              <button
                disabled={pending || !sourceDirty || !sourceText.trim()}
                onClick={() =>
                  source.id &&
                  void run({
                    operation: "autonomousEditSource",
                    id: source.id,
                    expectedRevision: sourceRevision,
                    text: sourceText,
                  })
                }
              >
                話題の変更を保存
              </button>
              <button
                disabled={pending || Boolean(sourceDirty)}
                onClick={() =>
                  source.id &&
                  void run({
                    operation:
                      field(source, "PublicUseAllowed") === "true"
                        ? "autonomousRevokeSource"
                        : "autonomousPermitSource",
                    id: source.id,
                    expectedRevision: sourceRevision,
                  })
                }
              >
                {field(source, "PublicUseAllowed") === "true"
                  ? "生成への利用を取り消す"
                  : "生成への利用を許可"}
              </button>
            </section>
          )}
          <label>
            新しい話題の本文
            <textarea
              aria-label="新しい話題の本文"
              value={newText}
              disabled={pending}
              onChange={(event) => setNewText(event.target.value)}
            />
          </label>
          <label>
            <input
              type="checkbox"
              checked={allowed}
              disabled={pending}
              onChange={(event) => setAllowed(event.target.checked)}
            />
            投稿案の生成に利用してよい
          </label>
          <button
            disabled={pending || !newText.trim()}
            onClick={() =>
              void run({
                operation: "autonomousAddSource",
                text: newText,
                publicUseAllowed: allowed,
              })
            }
          >
            話題を登録
          </button>
        </section>
      )}
    </section>
  );
}
