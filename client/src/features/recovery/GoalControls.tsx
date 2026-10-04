import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import { errorText, type Run } from "../../entities/models";
import type {
  WorkspaceResult,
  WorkspaceOperation,
} from "../../entities/workspace";
type Item = WorkspaceResult["items"][number];
type Action = "goalRun" | "goalVerify" | "goalCancel" | "goalReconcile";
const field = (item: Item, key: string) =>
  item.fields.find(([name]) => name === key)?.[1] ?? "";
export function GoalControls({
  call,
  serverId,
  projectId,
  onAccepted,
}: {
  call: Command;
  serverId: string | null;
  projectId: string;
  onAccepted: (run: Run) => void;
}) {
  const owner = JSON.stringify([serverId, projectId]);
  const generation = useRef(0);
  const busy = useRef(false);
  const [snapshot, setSnapshot] = useState<{
    owner: string;
    items: Item[];
  } | null>(null);
  const [detail, setDetail] = useState<{ owner: string; item: Item } | null>(
    null,
  );
  const [review, setReview] = useState<{
    owner: string;
    item: Item;
    action: Action;
  } | null>(null);
  const [ack, setAck] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const revision = ++generation.current;
    setSnapshot(null);
    setDetail(null);
    setReview(null);
    setAck(false);
    setError(null);
    setMessage(null);
    busy.current = false;
    setPending(false);
    if (!serverId || !projectId) return;
    setPending(true);
    void call("workspace_execute", {
      serverId,
      operation: { operation: "goals", projectId },
    })
      .then((result) => {
        if (revision === generation.current)
          setSnapshot({
            owner,
            items: result.items.filter(
              (item) => item.id && field(item, "Project") === projectId,
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
  const selected = detail?.owner === owner ? detail.item : null;
  const confirmation = review?.owner === owner ? review : null;
  const valid = (item: Item | undefined, id: string, session?: string) =>
    !!item &&
    item.id === id &&
    field(item, "Project") === projectId &&
    !!field(item, "Session") &&
    (!session || field(item, "Session") === session);
  const inspect = async (id: string) => {
    if (!serverId || busy.current || !visible) return;
    busy.current = true;
    setPending(true);
    setReview(null);
    setDetail(null);
    setError(null);
    const revision = generation.current;
    try {
      const [state, history] = await Promise.all([
        call("workspace_execute", {
          serverId,
          operation: { operation: "goal", projectId, id },
        }),
        call("workspace_execute", {
          serverId,
          operation: { operation: "goalHistory", projectId, id },
        }),
      ]);
      if (revision !== generation.current) return;
      const item = state.items[0];
      if (
        !valid(item, id) ||
        !valid(history.items[0], id, field(item, "Session"))
      )
        throw "InvalidResponse";
      setDetail({
        owner,
        item: {
          ...item,
          fields: [
            ...item.fields,
            ...history.items[0].fields.filter(
              ([key]) => key === "History" || key === "Attempts",
            ),
          ],
        },
      });
    } catch (failure) {
      if (revision === generation.current) setError(errorText(failure));
    } finally {
      if (revision === generation.current) {
        busy.current = false;
        setPending(false);
      }
    }
  };
  const act = async (action: Action | "track") => {
    if (!serverId || busy.current || !visible || !selected) return;
    if (action !== "track" && confirmation?.action !== action) return;
    if (action === "goalReconcile" && !ack) return;
    busy.current = true;
    setPending(true);
    setError(null);
    const revision = generation.current;
    const id = selected.id!;
    try {
      let saved = selected;
      if (action !== "track") {
        const operation: WorkspaceOperation =
          action === "goalReconcile"
            ? {
                operation: action,
                projectId,
                id,
                expectedRunId: field(selected, "Run") || "none",
                acknowledgeUncertainSideEffects: true,
              }
            : { operation: action, projectId, id };
        const result = await call("workspace_execute", { serverId, operation });
        saved = result.items[0];
        if (!valid(saved, id, field(selected, "Session")))
          throw "InvalidResponse";
        if (revision === generation.current) {
          setDetail({ owner, item: saved });
          setSnapshot({
            owner,
            items: visible.items.map((item) => (item.id === id ? saved : item)),
          });
          setMessage("Goalの状態を更新しました。");
        }
      }
      if (action === "track" || (action === "goalRun" && field(saved, "Run"))) {
        const run = await call("goal_track", {
          serverId,
          projectId,
          goalId: id,
        });
        if (
          run.serverId !== serverId ||
          run.projectId !== projectId ||
          run.sessionId !== field(selected, "Session")
        )
          throw "InvalidResponse";
        onAccepted(run);
        if (revision === generation.current)
          setMessage(`接続したGoal Run: ${run.runId}`);
      }
    } catch (failure) {
      if (revision === generation.current) {
        setError(
          errorText(failure) +
            " 自動再送は行いません。Goalを更新して状態と履歴を確認し、受け付けられたRunは既存Runの追跡で確認してください。",
        );
        setDetail(null);
        setSnapshot(null);
      }
    } finally {
      if (revision === generation.current) {
        setReview(null);
        setAck(false);
        busy.current = false;
        setPending(false);
      }
    }
  };
  const startReview = (action: Action) => {
    if (selected) {
      setReview({ owner, item: selected, action });
      setAck(false);
    }
  };
  return (
    <section className="attention-pane" aria-label="Goalの管理">
      <h2>Goal</h2>
      <p>
        保存したGoalの完了条件・予算・履歴を確認して操作します。不確定Runの照合は予算を回復せず、再実行も行いません。
      </p>
      <button
        disabled={!serverId || !projectId || pending}
        onClick={() => setRefresh((value) => value + 1)}
      >
        Goalを更新
      </button>
      {pending && <p role="status">Goalを処理中…</p>}
      {error && <p role="alert">{error}</p>}
      {message && <p role="status">{message}</p>}
      {visible?.items.length === 0 && <p>保存されたGoalはありません。</p>}
      {visible?.items.map((item) => (
        <article className="card" key={item.id!}>
          <h3>{item.title}</h3>
          <p>
            {field(item, "Status")} / Session: {field(item, "Session")} / Runs:{" "}
            {field(item, "Runs")} / LLM calls: {field(item, "LLM calls")}
          </p>
          <button disabled={pending} onClick={() => void inspect(item.id!)}>
            Goalを確認
          </button>
        </article>
      ))}
      {selected && (
        <article className="card">
          <h3>Goalの現在状態と履歴</h3>
          <p>{selected.title}</p>
          {selected.fields.map(([key, value], index) => (
            <div key={`${key}:${index}`}>
              <h4>{key}</h4>
              <pre>{value}</pre>
            </div>
          ))}
          {!["RUNNING", "CANCELLED", "COMPLETED"].includes(
            field(selected, "Status"),
          ) && (
            <button disabled={pending} onClick={() => startReview("goalRun")}>
              Goalの実行内容を確認
            </button>
          )}
          <button disabled={pending} onClick={() => startReview("goalVerify")}>
            完了条件の検証内容を確認
          </button>
          {!["CANCELLED", "COMPLETED"].includes(field(selected, "Status")) && (
            <button
              disabled={pending}
              onClick={() => startReview("goalCancel")}
            >
              Goalの取消内容を確認
            </button>
          )}
          {field(selected, "Status") === "RUNNING" && (
            <button
              disabled={pending}
              onClick={() => startReview("goalReconcile")}
            >
              不確定Runの照合内容を確認
            </button>
          )}
          {field(selected, "Run") && (
            <button disabled={pending} onClick={() => void act("track")}>
              Goalの既存Runを追跡
            </button>
          )}
        </article>
      )}
      {confirmation && (
        <aside className="card" aria-label="Goal操作の確認">
          <h3>
            {
              {
                goalRun: "保存したSessionでGoalを実行しますか？",
                goalVerify:
                  "完了条件を検証しますか？一致したGoalは完了状態になります。",
                goalCancel: "Goalを取り消しますか？",
                goalReconcile:
                  "結果不明のRunを照合し、Goalを一時停止しますか？",
              }[confirmation.action]
            }
          </h3>
          <p>{confirmation.item.title}</p>
          <p>
            Project: {projectId} / Session:{" "}
            {field(confirmation.item, "Session")} / Run:{" "}
            {field(confirmation.item, "Run") || "未着手"}
          </p>
          <pre>{field(confirmation.item, "Criteria")}</pre>
          <p>
            Runs: {field(confirmation.item, "Runs")} / LLM calls:{" "}
            {field(confirmation.item, "LLM calls")}
          </p>
          {confirmation.action === "goalReconcile" && (
            <label>
              <input
                type="checkbox"
                checked={ack}
                disabled={pending}
                onChange={(event) => setAck(event.target.checked)}
              />
              現在の成果物と履歴を確認し、不明な副作用を確認したうえで照合します。実行中Runは先に通常のRun操作で停止してください。
            </label>
          )}
          <button
            disabled={
              pending || (confirmation.action === "goalReconcile" && !ack)
            }
            onClick={() => void act(confirmation.action)}
          >
            このGoal操作を実行
          </button>
          <button disabled={pending} onClick={() => setReview(null)}>
            Goal操作に戻る
          </button>
        </aside>
      )}
    </section>
  );
}
