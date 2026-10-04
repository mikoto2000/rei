import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import { errorText } from "../../entities/models";
import type { WorkspaceResult } from "../../entities/workspace";
type Item = WorkspaceResult["items"][number];
const field = (item: Item, key: string) =>
  item.fields.find(([name]) => name === key)?.[1] ?? "";
export function HumanAnswers({
  call,
  serverId,
  projectId,
}: {
  call: Command;
  serverId: string | null;
  projectId: string;
}) {
  const owner = JSON.stringify([serverId, projectId]);
  const [snapshot, setSnapshot] = useState<{
    owner: string;
    items: Item[];
  } | null>(null);
  const [draft, setDraft] = useState<{ id: string; text: string } | null>(null);
  const [review, setReview] = useState<{
    owner: string;
    item: Item;
    text: string;
    version: number;
  } | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const generation = useRef(0);
  const busy = useRef(false);
  useEffect(() => {
    const revision = ++generation.current;
    setSnapshot(null);
    setDraft(null);
    setReview(null);
    setError(null);
    setSuccess(null);
    busy.current = false;
    setPending(false);
    if (!serverId || !projectId) return;
    setPending(true);
    void call("workspace_execute", {
      serverId,
      operation: { operation: "dependencies", projectId },
    })
      .then((result) => {
        if (revision === generation.current)
          setSnapshot({
            owner,
            items: result.items.filter(
              (item) =>
                item.id &&
                field(item, "Project") === projectId &&
                field(item, "Kind") === "USER_ANSWER",
            ),
          });
      })
      .catch((failure) => {
        if (revision === generation.current) setError(errorText(failure));
      })
      .finally(() => {
        if (revision === generation.current) setPending(false);
      });
    return () => {
      generation.current++;
    };
  }, [call, serverId, projectId, owner, refresh]);
  const visible = snapshot?.owner === owner ? snapshot : null;
  const selected = review?.owner === owner ? review : null;
  const save = async () => {
    if (!serverId || !visible || !selected || busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    const revision = generation.current;
    try {
      const result = await call("workspace_execute", {
        serverId,
        operation: {
          operation: "dependencyAnswer",
          projectId,
          id: selected.item.id!,
          expectedVersion: selected.version,
          answer: selected.text,
        },
      });
      if (revision !== generation.current) return;
      const saved = result.items[0];
      if (
        !saved ||
        saved.id !== selected.item.id ||
        field(saved, "Project") !== projectId ||
        field(saved, "Session") !== field(selected.item, "Session") ||
        field(saved, "Kind") !== "USER_ANSWER" ||
        Number(field(saved, "Version")) !== selected.version + 1 ||
        !field(saved, "Answer").trim()
      )
        throw "InvalidResponse";
      setSnapshot({
        owner,
        items: visible.items.map((item) =>
          item.id === saved.id ? saved : item,
        ),
      });
      setDraft(null);
      setSuccess(
        "回答を保存しました。依存の完了と前提条件はサーバーで判定されます。",
      );
    } catch (failure) {
      if (revision === generation.current) {
        setSnapshot(null);
        setDraft(null);
        setError(
          errorText(failure) +
            " 回答の自動再送は行いません。質問を更新し、保存済み回答と現在の状態を確認してください。",
        );
      }
    } finally {
      if (revision === generation.current) {
        setReview(null);
        busy.current = false;
        setPending(false);
      }
    }
  };
  return (
    <section aria-label="人間回答待ち" className="attention-pane">
      <h2>人間回答待ち</h2>
      <p>
        回答を保存します。Toolの承認やRunの再開は、それぞれの操作で確認してください。
      </p>
      <button
        disabled={!serverId || !projectId || pending}
        onClick={() => setRefresh((value) => value + 1)}
      >
        質問を更新
      </button>
      {pending && <p role="status">回答情報を処理中…</p>}
      {error && <p role="alert">{error}</p>}
      {success && <p role="status">{success}</p>}
      {visible?.items.length === 0 && <p>人間回答待ちの依存はありません。</p>}
      {visible?.items.map((item) => {
        const version = Number(field(item, "Version"));
        const writable =
          ["RUNNING", "WAITING", "BLOCKED"].includes(field(item, "State")) &&
          Number.isSafeInteger(version) &&
          version >= 0 &&
          version < Number.MAX_SAFE_INTEGER;
        const text = draft?.id === item.id ? draft.text : "";
        return (
          <article className="card" key={item.id!}>
            <h3>{item.title}</h3>
            <p>
              依存: {item.id} / Session: {field(item, "Session")} / version:{" "}
              {field(item, "Version")} / {field(item, "State")}
            </p>
            <p>
              期限: {field(item, "Deadline")} / 前提条件:{" "}
              {field(item, "Prerequisites") || "なし"}
            </p>
            <p>理由: {field(item, "Reason")}</p>
            <p>保存済み回答: {field(item, "Answer") || "未回答"}</p>
            {writable && (
              <>
                <label>
                  回答
                  <textarea
                    aria-label="回答"
                    maxLength={4096}
                    disabled={pending || !!selected}
                    value={text}
                    onChange={(event) => {
                      setDraft({ id: item.id!, text: event.target.value });
                      setSuccess(null);
                    }}
                  />
                </label>
                <button
                  disabled={pending || !!selected || !text.trim()}
                  onClick={() => setReview({ owner, item, text, version })}
                >
                  回答内容を確認
                </button>
              </>
            )}
          </article>
        );
      })}
      {selected && (
        <aside className="card" aria-label="回答の確認">
          <h3>この質問への回答を保存しますか？</h3>
          <p>{selected.item.title}</p>
          <p>
            依存: {selected.item.id} / Session:{" "}
            {field(selected.item, "Session")} / version: {selected.version}
          </p>
          <pre>{selected.text}</pre>
          <button disabled={pending} onClick={() => void save()}>
            この回答を保存
          </button>
          <button disabled={pending} onClick={() => setReview(null)}>
            回答に戻る
          </button>
        </aside>
      )}
    </section>
  );
}
