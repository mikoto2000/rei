import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import { errorText, type Project, type Run } from "../../entities/models";
import type { WorkspaceResult } from "../../entities/workspace";
import { HumanAnswers } from "./HumanAnswers";
import { GoalControls } from "./GoalControls";
type Item = WorkspaceResult["items"][number];
const field = (item: Item, key: string) =>
  item.fields.find(([name]) => name === key)?.[1] ?? "";
export function Recovery({
  call,
  serverId,
  projects,
  onAccepted,
}: {
  call: Command;
  serverId: string | null;
  projects: Project[];
  onAccepted: (run: Run) => void;
}) {
  const [project, setProject] = useState(projects[0]?.id ?? "");
  const [rows, setRows] = useState<{ owner: string; items: Item[] } | null>(
    null,
  );
  const [detail, setDetail] = useState<{
    owner: string;
    task: string;
    saved: Item;
    check: Item;
  } | null>(null);
  const [confirm, setConfirm] = useState<"resume" | "abandon" | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [accepted, setAccepted] = useState<{
    owner: string;
    run: string;
  } | null>(null);
  const generation = useRef(0);
  const busy = useRef(false);
  const owner = JSON.stringify([serverId, project]);
  useEffect(() => {
    generation.current++;
    setProject(projects[0]?.id ?? "");
    setRows(null);
    setDetail(null);
    setConfirm(null);
    setAccepted(null);
  }, [serverId, projects]);
  useEffect(() => {
    const revision = ++generation.current;
    setRows(null);
    setDetail(null);
    setConfirm(null);
    setError(null);
    setPending(false);
    busy.current = false;
    if (!serverId || !projects.some((p) => p.id === project)) return;
    setPending(true);
    void call("workspace_execute", {
      serverId,
      operation: { operation: "checkpoints", projectId: project },
    })
      .then((result) => {
        if (revision === generation.current)
          setRows({
            owner,
            items: result.items.filter(
              (i) => i.id && field(i, "Project") === project,
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
  }, [call, serverId, project, projects, owner, refresh]);
  const visible =
    serverId && projects.some((p) => p.id === project) && rows?.owner === owner
      ? rows
      : null;
  const selected = detail?.owner === owner ? detail : null;
  const inspect = async (task: string) => {
    if (!serverId || busy.current || !visible) return;
    busy.current = true;
    setPending(true);
    setError(null);
    setConfirm(null);
    setDetail(null);
    const revision = generation.current;
    try {
      const [saved, check] = await Promise.all([
        call("workspace_execute", {
          serverId,
          operation: {
            operation: "checkpoint",
            projectId: project,
            taskId: task,
          },
        }),
        call("workspace_execute", {
          serverId,
          operation: {
            operation: "checkpointInspect",
            projectId: project,
            taskId: task,
          },
        }),
      ]);
      if (revision !== generation.current) return;
      const state = saved.items[0],
        comparison = check.items[0];
      if (
        !state ||
        !comparison ||
        state.id !== task ||
        comparison.id !== task ||
        field(state, "Project") !== project ||
        field(comparison, "Project") !== project
      )
        throw "InvalidResponse";
      setDetail({ owner, task, saved: state, check: comparison });
    } catch (failure) {
      if (revision === generation.current) setError(errorText(failure));
    } finally {
      if (revision === generation.current) {
        busy.current = false;
        setPending(false);
      }
    }
  };
  const act = async (action: "resume" | "track" | "abandon") => {
    if (!serverId || busy.current || !visible || !selected) return;
    if (
      action === "resume" &&
      (!["CONTINUE", "CONFIRMATION_REQUIRED"].includes(
        field(selected.check, "Decision"),
      ) ||
        confirm !== "resume")
    )
      return;
    if (action === "abandon" && confirm !== "abandon") return;
    busy.current = true;
    setPending(true);
    setError(null);
    const revision = generation.current;
    const task = selected.task;
    try {
      if (action === "abandon")
        await call("workspace_execute", {
          serverId,
          operation: {
            operation: "checkpointAbandon",
            projectId: project,
            taskId: task,
          },
        });
      else {
        const run = await call(
          action === "resume" ? "checkpoint_resume" : "checkpoint_track",
          { serverId, projectId: project, taskId: task },
        );
        onAccepted(run);
        if (revision === generation.current)
          setAccepted({ owner, run: run.runId });
      }
      if (revision === generation.current) {
        setConfirm(null);
        setRefresh((v) => v + 1);
      }
    } catch (failure) {
      if (revision === generation.current) {
        setConfirm(null);
        setError(
          errorText(failure) +
            " 再開が受け付けられた可能性がある場合は、更新・状態照合後に既存Runを確認してください。再開の自動再送は行いません。",
        );
      }
    } finally {
      if (revision === generation.current) {
        busy.current = false;
        setPending(false);
      }
    }
  };
  return (
    <section
      className="page narrow attention-pane"
      aria-label="Checkpointの復旧"
    >
      <h1>復旧・再開</h1>
      <label>
        Project
        <select
          aria-label="復旧のProject"
          value={project}
          disabled={!serverId}
          onChange={(e) => setProject(e.target.value)}
        >
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>
      <button
        disabled={!serverId || pending || !project}
        onClick={() => setRefresh((v) => v + 1)}
      >
        更新
      </button>
      <p>
        保存状態を現在のファイル・Git・プロセス・実行結果と照合し、再開前に差分を確認します。保存されたRUNNINGは現在の実行中を意味するとは限りません。
      </p>
      {!serverId && <p>サーバーを選択し、認証を解除してください。</p>}
      {pending && <p role="status">処理中…</p>}
      {error && <p role="alert">{error}</p>}
      {accepted?.owner === owner && (
        <p>接続したRun: {accepted.run}。実行一覧から状態を確認できます。</p>
      )}
      {visible?.items.length === 0 && <p>保存されたCheckpointはありません。</p>}
      {visible?.items.map((item) => (
        <article className="card" key={item.id!}>
          <h2>{item.title}</h2>
          <p>
            Task: {item.id} / Session: {field(item, "Session")} / Run:{" "}
            {field(item, "Run")} / revision: {field(item, "Revision")} /{" "}
            {field(item, "Status")}
          </p>
          <button disabled={pending} onClick={() => void inspect(item.id!)}>
            状態を照合
          </button>
        </article>
      ))}
      {selected && (
        <article className="card">
          <h2>照合結果</h2>
          <p>
            Task: {selected.task} / Project: {project} / Session:{" "}
            {field(selected.saved, "Session")} / revision:{" "}
            {field(selected.saved, "Revision")}
          </p>
          <pre>{selected.saved.title}</pre>
          {selected.check.fields
            .filter(([key]) => key !== "Project")
            .map(([key, value]) => (
              <div key={key}>
                <h3>{key}</h3>
                <pre>{value}</pre>
              </div>
            ))}
          <p>
            結果不明の操作は、結果を個別に確認するまで副作用を再実行しません。サーバーは再開直前に状態を再照合します。
          </p>
          {["CONTINUE", "CONFIRMATION_REQUIRED"].includes(
            field(selected.check, "Decision"),
          ) && (
            <button disabled={pending} onClick={() => setConfirm("resume")}>
              再開内容を確認
            </button>
          )}
          <button disabled={pending} onClick={() => void act("track")}>
            既存Runを確認
          </button>
          <button disabled={pending} onClick={() => setConfirm("abandon")}>
            放棄内容を確認
          </button>
          {confirm && (
            <aside aria-label="復旧操作の確認">
              <h3>
                {confirm === "resume"
                  ? "このTaskを保存したSessionで再開しますか？"
                  : "このTaskの保存状態を放棄しますか？"}
              </h3>
              <p>{selected.saved.title}</p>
              <button disabled={pending} onClick={() => void act(confirm)}>
                {confirm === "resume" ? "このTaskを再開" : "このTaskを放棄"}
              </button>
              <button disabled={pending} onClick={() => setConfirm(null)}>
                戻る
              </button>
            </aside>
          )}
        </article>
      )}
      <HumanAnswers
        call={call}
        serverId={serverId}
        projectId={projects.some((p) => p.id === project) ? project : ""}
      />
      <GoalControls
        call={call}
        serverId={serverId}
        projectId={projects.some((p) => p.id === project) ? project : ""}
        onAccepted={onAccepted}
      />
    </section>
  );
}
