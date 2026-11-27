import type { LlmPort } from "./llmSelection";
import { LlmSelection } from "./llmSelection";
import { useEffect, useMemo, useSyncExternalStore } from "react";
import { command, type Command } from "../../tauri/commands";
import { errorText } from "../../entities/models";
export interface LlmControlsProps {
  serverId: string;
  port?: LlmPort;
  disabled?: boolean;
  onBusy?: (busy: boolean) => void;
  call?: Command;
}
export function LlmControls({
  serverId,
  port,
  disabled,
  onBusy,
  call = command,
}: LlmControlsProps) {
  const selection = useMemo(
    () =>
      new LlmSelection(
        port ?? {
          endpoints: (serverId) => call("llm_endpoints", { serverId }),
          select: (serverId, endpoint) =>
            call("llm_select", { serverId, endpoint }),
          models: (serverId) => call("llm_models", { serverId }),
          selectModel: (serverId, endpoint, model) =>
            call("llm_select_model", { serverId, endpoint, model }),
        },
        serverId,
      ),
    [serverId, port, call],
  );
  const state = useSyncExternalStore(selection.subscribe, selection.snapshot);
  useEffect(() => {
    void selection.refresh();
  }, [selection]);
  useEffect(() => {
    const notify = () => onBusy?.(selection.snapshot().busy);
    notify();
    const unsubscribe = selection.subscribe(notify);
    return () => {
      unsubscribe();
      onBusy?.(false);
    };
  }, [selection, onBusy]);
  const current = state.endpoints.find((e) => e.active);
  return (
    <section className="llm-controls" aria-label="通常チャットのLLM">
      <p>
        通常チャットの接続先はサーバー全体で共有され、次のRunから適用されます。再起動で設定値に戻ります。実行中・承認待ち・再開するRunは固定した接続先を使います。
      </p>
      <fieldset disabled={disabled || state.busy}>
        <legend>LLM接続先とモデル</legend>
        {current && (
          <>
            <label>
              LLM接続先
              <select
                aria-label="LLM接続先"
                value={current.profile.id}
                onChange={(e) => void selection.select(e.target.value)}
              >
                {state.endpoints.map((e) => (
                  <option key={e.profile.id} value={e.profile.id}>
                    {e.profile.id}
                  </option>
                ))}
              </select>
            </label>
            <p>
              モデル: {current.profile.model ?? "未設定"} /
              利用経路（設定による申告）:{" "}
              {current.profile.usagePath ?? "unknown"}
            </p>
            <p>
              認証: {current.authenticationState} / アカウント:{" "}
              {current.profile.accountRef ?? "なし"}
            </p>
            {current.unavailableReason && (
              <p role="status">利用不可: {current.unavailableReason}</p>
            )}
            <button type="button" onClick={() => void selection.loadModels()}>
              モデル一覧を取得
            </button>
            {state.models && (
              <label>
                LLMモデル
                <select
                  aria-label="LLMモデル"
                  value={state.models.currentModel ?? ""}
                  onChange={(e) => void selection.selectModel(e.target.value)}
                >
                  {!state.models.models.includes(
                    state.models.currentModel ?? "",
                  ) && (
                    <option value={state.models.currentModel ?? ""}>
                      {state.models.currentModel ?? "未設定"}
                    </option>
                  )}
                  {state.models.models.map((model) => (
                    <option key={model} value={model}>
                      {model}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <details>
              <summary>接続先の詳細</summary>
              <p>
                Provider: {current.profile.provider} / API:{" "}
                {current.profile.api} / 認証方式:{" "}
                {current.profile.authentication} / 推論再送:{" "}
                {current.reasoningReplay ?? "未確認"}
              </p>
              <p>
                能力は実装と運用者の宣言の積です。UNKNOWNは未確認です。実サービスでの推論動作は未検証です。
              </p>
              <pre>{JSON.stringify(current.profile.capabilities, null, 2)}</pre>
              <p>適用する生成設定</p>
              <pre>{JSON.stringify(current.generationSettings, null, 2)}</pre>
              <p>適用しない設定と理由</p>
              <pre>{JSON.stringify(current.unappliedSettings, null, 2)}</pre>
              {current.diagnostics.map((text, index) => (
                <p key={index}>{text}</p>
              ))}
            </details>
          </>
        )}
        <button type="button" onClick={() => void selection.refresh()}>
          接続先を更新
        </button>
      </fieldset>
      {state.busy && <p role="status">接続先の選択を更新しています…</p>}
      {state.error && (
        <p role="alert">
          {state.error === "EndpointNotFound"
            ? "このサーバーはLLM選択に対応していません。通常チャットは引き続き利用できます。"
            : errorText(state.error)}
        </p>
      )}
    </section>
  );
}
