import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import type {
  CoachingSettings,
  WorkspaceResult,
  WorkspaceOperation,
} from "../../entities/workspace";
import { errorText } from "../../entities/models";

const categories = [
  "development",
  "research",
  "documentation",
  "communication",
  "social",
  "media",
  "shopping",
  "gaming",
  "monitoring",
  "navigation",
  "idle",
];
function valid(s: CoachingSettings): boolean {
  return (
    !!s &&
    typeof s.enabled === "boolean" &&
    Array.isArray(s.categories) &&
    s.categories.length > 0 &&
    s.categories.length <= 11 &&
    new Set(s.categories).size === s.categories.length &&
    s.categories.every((c) => categories.includes(c)) &&
    Number.isFinite(s.targetShare) &&
    s.targetShare > 0 &&
    s.targetShare <= 1 &&
    Number.isInteger(s.minimumObservedMinutes) &&
    s.minimumObservedMinutes >= 1 &&
    s.minimumObservedMinutes <= 44640 &&
    Number.isFinite(s.minimumCoverage) &&
    s.minimumCoverage > 0 &&
    s.minimumCoverage <= 1 &&
    Number.isFinite(s.maximumUnknownShare) &&
    s.maximumUnknownShare >= 0 &&
    s.maximumUnknownShare <= 1 &&
    Number.isInteger(s.cooldownDays) &&
    s.cooldownDays >= 1 &&
    s.cooldownDays <= 366
  );
}
function decode(value: WorkspaceResult) {
  const item = value.items[0];
  if (value.items.length !== 1 || !item)
    throw new Error("設定応答を確認できません");
  const field = (name: string) =>
    item.fields.find(([key]) => key === name)?.[1];
  const revision = Number(field("Revision"));
  const settings = JSON.parse(field("Settings") ?? "null") as CoachingSettings;
  if (
    field("Scope") !== "LOCAL_DEVICE_COACHING_SETTINGS" ||
    field("Revision") === undefined ||
    !Number.isSafeInteger(revision) ||
    revision < 0 ||
    !valid(settings)
  )
    throw new Error("設定応答を確認できません");
  return { revision, settings };
}
function describe(s: CoachingSettings) {
  return `分類: ${s.categories.join(", ")} / 基準比率: ${s.targetShare * 100}% / 最低観測: ${s.minimumObservedMinutes}分 / 最低coverage: ${s.minimumCoverage * 100}% / 最大不明比率: ${s.maximumUnknownShare * 100}% / cooldown: ${s.cooldownDays}日 / ${s.enabled ? "有効" : "無効"}`;
}
type Intent =
  | { kind: "configure"; settings: CoachingSettings }
  | { kind: "enabled"; enabled: boolean };
export function ActivityCoachingSettings({
  call,
  serverId,
}: {
  call: Command;
  serverId: string | null;
}) {
  const [saved, setSaved] = useState<
    ({ owner: string } & ReturnType<typeof decode>) | null
  >(null);
  const [draft, setDraft] = useState<CoachingSettings | null>(null);
  const [intent, setIntent] = useState<Intent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const generation = useRef(0);
  const busy = useRef(false);
  useEffect(() => {
    generation.current++;
    setSaved(null);
    setDraft(null);
    setIntent(null);
    setError(null);
    setPending(false);
    busy.current = false;
    return () => {
      generation.current++;
    };
  }, [serverId]);
  const visible = saved?.owner === serverId ? saved : null;
  const execute = async (operation: WorkspaceOperation) => {
    if (!serverId || busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    setIntent(null);
    const current = generation.current;
    try {
      const value = decode(
        await call("workspace_execute", { serverId, operation }),
      );
      if (current === generation.current) {
        setSaved({ owner: serverId, ...value });
        setDraft({
          ...value.settings,
          enabled: false,
          categories: [...value.settings.categories],
        });
      }
    } catch (failure) {
      if (current === generation.current) {
        setSaved(null);
        setDraft(null);
        setError(
          `${errorText(failure)} 保存状態を再取得して確認してください。`,
        );
      }
    } finally {
      if (current === generation.current) {
        busy.current = false;
        setPending(false);
      }
    }
  };
  const change = (settings: CoachingSettings) => {
    setDraft(settings);
    setIntent(null);
    setError(null);
  };
  const review = () => {
    if (!draft || !valid(draft)) {
      setError("分類と比率・観測量・cooldownの入力を確認してください。");
      return;
    }
    setIntent({
      kind: "configure",
      settings: { ...draft, enabled: false, categories: [...draft.categories] },
    });
  };
  const confirm = () => {
    if (!visible || !intent) return;
    void execute(
      intent.kind === "configure"
        ? {
            operation: "activityCoachingConfigure",
            expectedRevision: visible.revision,
            settings: intent.settings,
          }
        : {
            operation: "activityCoachingEnabled",
            expectedRevision: visible.revision,
            enabled: intent.enabled,
          },
    );
  };
  return (
    <section aria-label="Coaching設定">
      <h3>Coaching 設定</h3>
      <p>
        接続先の端末全体に適用する分類基準です。助言は観測からの推定です。設定を保存すると無効状態になり、有効化は別に確認します。
      </p>
      <button
        disabled={!serverId || pending}
        onClick={() => void execute({ operation: "activityCoachingSettings" })}
      >
        保存済み設定を取得
      </button>
      {pending && <p role="status">設定を確認しています…</p>}
      {error && <p role="alert">{error}</p>}
      {visible && draft && (
        <>
          <p>保存済み基準: {describe(visible.settings)}</p>
          <fieldset disabled={pending}>
            <legend>変更する分類基準</legend>
            {categories.map((category) => (
              <label key={category}>
                <input
                  type="checkbox"
                  checked={draft.categories.includes(category)}
                  onChange={(event) =>
                    change({
                      ...draft,
                      categories: event.target.checked
                        ? [...draft.categories, category]
                        : draft.categories.filter((c) => c !== category),
                    })
                  }
                />
                {category}
              </label>
            ))}
            <label>
              基準比率（%）
              <input
                type="number"
                min="0.1"
                max="100"
                step="0.1"
                value={draft.targetShare * 100}
                onChange={(e) =>
                  change({
                    ...draft,
                    targetShare: Number(e.target.value) / 100,
                  })
                }
              />
            </label>
            <label>
              最低観測時間（分）
              <input
                type="number"
                min="1"
                max="44640"
                value={draft.minimumObservedMinutes}
                onChange={(e) =>
                  change({
                    ...draft,
                    minimumObservedMinutes: Number(e.target.value),
                  })
                }
              />
            </label>
            <label>
              最低coverage（%）
              <input
                type="number"
                min="0.1"
                max="100"
                step="0.1"
                value={draft.minimumCoverage * 100}
                onChange={(e) =>
                  change({
                    ...draft,
                    minimumCoverage: Number(e.target.value) / 100,
                  })
                }
              />
            </label>
            <label>
              最大不明比率（%）
              <input
                type="number"
                min="0"
                max="100"
                step="0.1"
                value={draft.maximumUnknownShare * 100}
                onChange={(e) =>
                  change({
                    ...draft,
                    maximumUnknownShare: Number(e.target.value) / 100,
                  })
                }
              />
            </label>
            <label>
              助言間隔（日）
              <input
                type="number"
                min="1"
                max="366"
                value={draft.cooldownDays}
                onChange={(e) =>
                  change({ ...draft, cooldownDays: Number(e.target.value) })
                }
              />
            </label>
            <button onClick={review}>設定変更を確認</button>
            <button
              onClick={() =>
                setIntent({
                  kind: "enabled",
                  enabled: !visible.settings.enabled,
                })
              }
            >
              {visible.settings.enabled ? "無効化を確認" : "有効化を確認"}
            </button>
          </fieldset>
          {intent && (
            <div role="group" aria-label="Coaching変更確認">
              <p>確認した保存済み基準: {describe(visible.settings)}</p>
              <p>
                変更後:{" "}
                {describe(
                  intent.kind === "configure"
                    ? intent.settings
                    : { ...visible.settings, enabled: intent.enabled },
                )}
              </p>
              <button disabled={pending} onClick={confirm}>
                {intent.kind === "configure"
                  ? "無効状態で保存"
                  : intent.enabled
                    ? "保存済み基準を有効化"
                    : "保存済み基準を無効化"}
              </button>
              <button disabled={pending} onClick={() => setIntent(null)}>
                確認を閉じる
              </button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
