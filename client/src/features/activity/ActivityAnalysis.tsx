import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import type { WorkspaceResult } from "../../entities/workspace";
import { errorText } from "../../entities/models";
import { ActivityCoachingSettings } from "./ActivityCoachingSettings";
export function ActivityAnalysis({
  call,
  serverId,
}: {
  call: Command;
  serverId: string | null;
}) {
  const [period, setPeriod] = useState<"WEEK" | "MONTH">("WEEK");
  const [date, setDate] = useState("");
  const [result, setResult] = useState<{
    owner: string;
    value: WorkspaceResult;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const generation = useRef(0);
  const busy = useRef(false);
  useEffect(() => {
    generation.current++;
    setResult(null);
    setError(null);
    setPending(false);
    busy.current = false;
    return () => {
      generation.current++;
    };
  }, [serverId]);
  const reset = () => {
    generation.current++;
    setResult(null);
    setError(null);
    setPending(false);
    busy.current = false;
  };
  const load = async () => {
    if (!serverId || busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    setResult(null);
    const revision = generation.current;
    try {
      const value = await call("workspace_execute", {
        serverId,
        operation: {
          operation: "activityAnalysis",
          period,
          date: date || null,
        },
      });
      if (revision === generation.current)
        setResult({ owner: serverId, value });
    } catch (failure) {
      if (revision === generation.current) setError(errorText(failure));
    } finally {
      if (revision === generation.current) {
        busy.current = false;
        setPending(false);
      }
    }
  };
  const visible = result?.owner === serverId ? result.value : null;
  return (
    <section aria-label="Activity分析">
      <h2>Activity 週次・月次分析</h2>
      <p>
        接続先の端末全体の保存観測を分析します。観測量・分類・集中候補は推定で、成果や集中の実測ではありません。
      </p>
      <label>
        分析期間
        <select
          value={period}
          onChange={(e) => {
            reset();
            setPeriod(e.target.value as "WEEK" | "MONTH");
          }}
        >
          <option value="WEEK">週次</option>
          <option value="MONTH">月次</option>
        </select>
      </label>
      <label>
        対象日
        <input
          type="date"
          value={date}
          onChange={(e) => {
            reset();
            setDate(e.target.value);
          }}
        />
      </label>
      <p>
        空欄なら直近の完了した期間を表示します。指定日を含む暦の週・月と直前期間を比較します。
      </p>
      <button disabled={!serverId || pending} onClick={() => void load()}>
        保存観測を分析
      </button>
      {pending && <p role="status">保存観測を読み取っています…</p>}
      {error && <p role="alert">{error}</p>}
      <ActivityCoachingSettings call={call} serverId={serverId} />
      {visible?.items.map((item, index) => (
        <article key={index}>
          <h3>{item.title}</h3>
          <p>時間帯: {item.fields.find(([key]) => key === "Zone")?.[1]}</p>
          {item.fields.some(
            ([key, value]) => key === "Partial" && value === "true",
          ) && <p>途中期間のため、直前の全期間との比較になります。</p>}
          <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
            {item.fields.find(([key]) => key === "Report")?.[1]}
          </pre>
        </article>
      ))}
    </section>
  );
}
