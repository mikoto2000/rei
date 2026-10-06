import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import { errorText, type Project } from "../../entities/models";
import type { WorkspaceResult } from "../../entities/workspace";
export function ActivityObservationContext({
  call,
  serverId,
  projects,
}: {
  call: Command;
  serverId: string | null;
  projects: Project[];
}) {
  const [project, setProject] = useState("");
  const [date, setDate] = useState("");
  const [snapshot, setSnapshot] = useState<{
    owner: string;
    value: WorkspaceResult;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const generation = useRef(0);
  const busy = useRef(false);
  const owner = JSON.stringify([serverId, project, date]);
  const reset = () => {
    generation.current++;
    setSnapshot(null);
    setError(null);
    setPending(false);
    busy.current = false;
  };
  useEffect(() => {
    reset();
    setProject("");
    return () => {
      generation.current++;
    };
  }, [serverId, projects]);
  const selected = projects.some((p) => p.id === project);
  const load = async () => {
    if (!serverId || !selected || busy.current) return;
    busy.current = true;
    setPending(true);
    setSnapshot(null);
    setError(null);
    const current = generation.current;
    try {
      const value = await call("workspace_execute", {
        serverId,
        operation: {
          operation: "activityObservationContext",
          projectId: project,
          date: date || null,
        },
      });
      if (current !== generation.current) return;
      if (
        value.items.length !== 1 ||
        value.items[0].fields.find(([key]) => key === "Scope")?.[1] !==
          "PROJECT_OBSERVATION_CONTEXT" ||
        value.items[0].fields.find(([key]) => key === "Project")?.[1] !==
          project
      )
        throw new Error("選択したProjectの保存文脈を確認できません");
      setSnapshot({ owner, value });
    } catch (failure) {
      if (current === generation.current) setError(errorText(failure));
    } finally {
      if (current === generation.current) {
        busy.current = false;
        setPending(false);
      }
    }
  };
  const visible = selected && snapshot?.owner === owner ? snapshot.value : null;
  return (
    <section aria-label="保存観測時文脈">
      <h3>Activity / Work Context 保存観測時文脈</h3>
      <p>
        観測時に保存された前景アプリ・Git・Work
        Context・Tool出典を読みます。現在の文脈から補完しません。画面操作やタスクへの従事・成果の証明ではありません。
      </p>
      <label>
        文脈のProject
        <select
          value={project}
          onChange={(e) => {
            reset();
            setProject(e.target.value);
          }}
        >
          <option value="">選択してください</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        文脈の対象日
        <input
          type="date"
          value={date}
          onChange={(e) => {
            reset();
            setDate(e.target.value);
          }}
        />
      </label>
      <p>空欄は接続先の journal 時間帯での今日です。</p>
      <button
        disabled={!serverId || !selected || pending}
        onClick={() => void load()}
      >
        保存観測時の文脈を取得
      </button>
      {pending && <p role="status">保存された出典を読んでいます…</p>}
      {error && <p role="alert">{error}</p>}
      {visible?.items.map((item, index) => {
        const field = (key: string) =>
          item.fields.find(([name]) => name === key)?.[1];
        return (
          <article key={index}>
            <h4>{item.title}</h4>
            <p>
              時間帯: {field("Zone")} / 保存観測: {field("LinkedObservations")}
              件 / 出典欠落: {field("MissingContextRecords")}件
            </p>
            {field("Partial") === "true" && (
              <p>
                保存文脈または表示範囲が不完全です。件数は確認できた範囲を示します。
              </p>
            )}
            <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
              {field("Report")}
            </pre>
          </article>
        );
      })}
    </section>
  );
}
