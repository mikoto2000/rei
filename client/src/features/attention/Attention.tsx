import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import { errorText, type Project } from "../../entities/models";
import type {
  WorkspaceOperation,
  WorkspaceResult,
} from "../../entities/workspace";
type Item = WorkspaceResult["items"][number];
const field = (item: Item, key: string) =>
  item.fields.find(([name]) => name === key)?.[1] ?? "";
export function Attention({
  call,
  serverId,
  projects,
}: {
  call: Command;
  serverId: string | null;
  projects: Project[];
}) {
  const [project, setProject] = useState(projects[0]?.id ?? "");
  const [snapshot, setSnapshot] = useState<{
    owner: string;
    notices: Item[];
    approvals: Item[];
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [review, setReview] = useState<Item | null>(null);
  const [refresh, setRefresh] = useState(0);
  const generation = useRef(0);
  const busy = useRef(false);
  const owner = JSON.stringify([serverId, project]);
  useEffect(() => {
    generation.current++;
    setProject(projects[0]?.id ?? "");
    setSnapshot(null);
    setReview(null);
    busy.current = false;
    setPending(false);
  }, [serverId, projects]);
  useEffect(() => {
    const revision = ++generation.current;
    setSnapshot(null);
    setReview(null);
    setError(null);
    setPending(false);
    busy.current = false;
    if (!serverId || !projects.some((p) => p.id === project)) return;
    setPending(true);
    void Promise.allSettled([
      call("workspace_execute", {
        serverId,
        operation: { operation: "attention", projectId: project },
      }),
      call("workspace_execute", {
        serverId,
        operation: { operation: "approvals", projectId: project },
      }),
    ]).then((results) => {
      if (revision !== generation.current) return;
      const read = (index: number) => {
        const result = results[index];
        return result.status === "fulfilled"
          ? result.value.items.filter((i) => field(i, "Project") === project)
          : [];
      };
      setSnapshot({ owner, notices: read(0), approvals: read(1) });
      setPending(false);
      const failure = results.find((r) => r.status === "rejected");
      if (failure?.status === "rejected") setError(errorText(failure.reason));
    });
    return () => {
      generation.current++;
    };
  }, [call, serverId, project, projects, owner, refresh]);
  const mutate = async (operation: WorkspaceOperation) => {
    if (
      !serverId ||
      busy.current ||
      snapshot?.owner !== owner ||
      !projects.some((p) => p.id === project)
    )
      return;
    busy.current = true;
    setPending(true);
    setError(null);
    const revision = generation.current;
    try {
      await call("workspace_execute", { serverId, operation });
      if (revision === generation.current) {
        setReview(null);
        setRefresh((value) => value + 1);
      }
    } catch (failure) {
      if (revision === generation.current) setError(errorText(failure));
    } finally {
      if (revision === generation.current) {
        busy.current = false;
        setPending(false);
      }
    }
  };
  const visible =
    serverId &&
    projects.some((p) => p.id === project) &&
    snapshot?.owner === owner
      ? snapshot
      : null;
  return (
    <section className="page narrow attention-pane" aria-label="Inboxと承認">
      <h1>Inbox・承認</h1>
      <label>
        Project
        <select
          aria-label="InboxのProject"
          value={project}
          onChange={(event) => setProject(event.target.value)}
          disabled={!serverId}
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
      {!serverId && <p>サーバーを選択し、認証を解除してください。</p>}
      {error && <p role="alert">{error}</p>}
      {pending && <p role="status">処理中…</p>}
      <h2>通知</h2>
      {visible?.notices.length === 0 && <p>未確認の通知はありません。</p>}
      {visible?.notices.map((item) => (
        <article className="card" key={item.id ?? item.title}>
          <h3>{item.title}</h3>
          <p style={{ whiteSpace: "pre-wrap" }}>{field(item, "Message")}</p>
          <p>
            Session: {field(item, "Session")} / Run:{" "}
            {field(item, "Run") || "なし"} / 対象: {field(item, "Reference")}
          </p>
          {item.id && field(item, "Status") === "OPEN" && (
            <button
              disabled={pending}
              onClick={() =>
                void mutate({
                  operation: "attentionAck",
                  projectId: project,
                  id: item.id!,
                })
              }
            >
              通知を確認済みにする
            </button>
          )}
        </article>
      ))}
      <h2>Toolの承認</h2>
      <p>
        承認は一回の呼び出しに対する判断です。通知の確認や承認だけでは実行を再開しません。
      </p>
      {visible?.approvals.length === 0 && <p>有効な承認要求はありません。</p>}
      {visible?.approvals.map((item) => (
        <article className="card" key={item.id ?? item.title}>
          <h3>{item.title}</h3>
          <pre>{field(item, "Arguments")}</pre>
          <p>
            Session: {field(item, "Session")} / Run: {field(item, "Run")} /
            期限: {field(item, "Expires")} / {field(item, "Status")}
          </p>
          {item.id && field(item, "Status") === "PENDING" && (
            <>
              <button disabled={pending} onClick={() => setReview(item)}>
                承認内容を確認
              </button>
              <button
                disabled={pending}
                onClick={() =>
                  void mutate({
                    operation: "approvalDecision",
                    projectId: project,
                    id: item.id!,
                    approved: false,
                  })
                }
              >
                拒否
              </button>
            </>
          )}
        </article>
      ))}
      {review && visible?.approvals.includes(review) && (
        <aside className="card" aria-label="承認の確認">
          <h2>このTool呼び出しを一回だけ承認しますか？</h2>
          <p>{review.title}</p>
          <pre>{field(review, "Arguments")}</pre>
          <p>
            Project: {project} / Session: {field(review, "Session")} / Run:{" "}
            {field(review, "Run")}
          </p>
          <button
            disabled={pending}
            onClick={() =>
              void mutate({
                operation: "approvalDecision",
                projectId: project,
                id: review.id!,
                approved: true,
              })
            }
          >
            一回だけ承認
          </button>
          <button disabled={pending} onClick={() => setReview(null)}>
            戻る
          </button>
        </aside>
      )}
    </section>
  );
}
