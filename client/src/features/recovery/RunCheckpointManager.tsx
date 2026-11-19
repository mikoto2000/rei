import { useEffect, useState } from "react";
import { errorText } from "../../entities/models";
import type { WorkspaceResult } from "../../entities/workspace";
import type { Command } from "../../tauri/commands";
import { DiscardConfirmation } from "./DiscardConfirmation";

type Item = WorkspaceResult["items"][number];
const field = (item: Item, key: string) =>
  item.fields.find(([name]) => name === key)?.[1] ?? "";

export function RunCheckpointManager({
  call,
  serverId,
}: {
  call: Command;
  serverId: string;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button aria-expanded={open} onClick={() => setOpen(!open)}>
        実行とチェックポイントを管理
      </button>
      {open && <ManagedRuns key={serverId} call={call} serverId={serverId} />}
    </>
  );
}

function ManagedRuns({ call, serverId }: { call: Command; serverId: string }) {
  const [runs, setRuns] = useState<Item[]>([]);
  const [run, setRun] = useState<Item | null>(null);
  const [checkpoints, setCheckpoints] = useState<Item[]>([]);
  const [confirm, setConfirm] = useState<Item | null>(null);
  const [pending, setPending] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    let current = true;
    void call("workspace_execute", {
      serverId,
      operation: { operation: "managedRuns" },
    })
      .then((result) => {
        if (current) setRuns(result.items);
      })
      .catch((failure) => {
        if (current) setError(errorText(failure));
      })
      .finally(() => {
        if (current) setPending(false);
      });
    return () => {
      current = false;
    };
  }, [call, serverId]);

  async function refresh(selected: Item | null, includeList: boolean) {
    const projectId = selected ? field(selected, "Project") : "";
    const [details, result] = await Promise.all([
      selected?.id && projectId
        ? call("workspace_execute", {
            serverId,
            operation: {
              operation: "runCheckpoints",
              projectId,
              runId: selected.id,
            },
          })
        : null,
      includeList
        ? call("workspace_execute", {
            serverId,
            operation: { operation: "managedRuns" },
          })
        : null,
    ]);
    setCheckpoints(details?.items ?? []);
    if (result) setRuns(result.items);
  }

  async function load(selected: Item | null, includeList: boolean) {
    setRun(selected);
    setCheckpoints([]);
    setConfirm(null);
    setError("");
    setNotice("");
    setPending(true);
    try {
      await refresh(selected, includeList);
    } catch (failure) {
      setError(errorText(failure));
    } finally {
      setPending(false);
    }
  }

  async function discard() {
    if (!run?.id || !confirm?.id || pending) return;
    setPending(true);
    setConfirm(null);
    setCheckpoints([]);
    setError("");
    try {
      await call("workspace_execute", {
        serverId,
        operation: {
          operation: "discardRunCheckpoint",
          projectId: field(run, "Project"),
          runId: run.id,
          taskId: confirm.id,
          revision: field(confirm, "Revision"),
        },
      });
      setNotice("チェックポイントを破棄しました。");
    } catch (failure) {
      setError(
        "破棄できませんでした。最新状態を再取得します。 " + errorText(failure),
      );
    }
    try {
      await refresh(run, true);
    } catch (failure) {
      setError("最新状態を取得できませんでした。 " + errorText(failure));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="run-list">
      <h2>実行中・失敗・停止済みの Run</h2>
      <button disabled={pending} onClick={() => void load(run, true)}>
        更新
      </button>
      {error && <p role="alert">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      {pending && <p role="status">処理中…</p>}
      {!pending && !error && runs.length === 0 && (
        <p>対象の Run はありません。</p>
      )}
      {runs.map((row) => (
        <article
          className="card"
          key={JSON.stringify([field(row, "Project"), row.id])}
        >
          <button
            className="run-link"
            disabled={pending || !row.id}
            onClick={() => void load(row, false)}
          >
            <strong>{row.title || row.id}</strong>
            <span>
              {field(row, "Project name") || "プロジェクト未設定"} /{" "}
              {field(row, "Status")} / {field(row, "Started") || "開始時刻不明"}
            </span>
            {field(row, "Failure") && <span>{field(row, "Failure")}</span>}
          </button>
        </article>
      ))}
      {run && (
        <article className="card" aria-label="Run の詳細">
          <h3>Run {run.id}</h3>
          {!pending && !error && checkpoints.length === 0 && (
            <p>チェックポイントがありません。</p>
          )}
          {checkpoints.map((saved) => (
            <div key={saved.id}>
              <p>
                {saved.title} / タスク {saved.id}
              </p>
              <p>
                保存時刻 {field(saved, "Saved")} / {field(saved, "Status")} /
                revision {field(saved, "Revision")}
              </p>
              <p>
                保存元 Run {field(saved, "Run")} / 起点 Run{" "}
                {field(saved, "Original run")}
              </p>
              <button
                disabled={
                  pending || !saved.id || field(saved, "Discardable") !== "true"
                }
                onClick={() => {
                  setNotice("");
                  setConfirm(saved);
                }}
              >
                破棄内容を確認
              </button>
            </div>
          ))}
          {confirm && (
            <DiscardConfirmation
              task={confirm.id!}
              request={confirm.title}
              pending={pending}
              onDiscard={() => void discard()}
              onBack={() => setConfirm(null)}
            />
          )}
        </article>
      )}
    </div>
  );
}
