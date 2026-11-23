import type { Connection } from "../entities/models";
export function ConnectionStatus({
  connection,
  configured,
  unlocked,
}: {
  connection?: Connection;
  configured: boolean;
  unlocked: boolean;
}) {
  const status = !configured
    ? { kind: "unknown", text: "サーバー未設定" }
    : !unlocked
      ? { kind: "unknown", text: "接続情報を開いてください" }
      : !connection || connection.state === "DISCONNECTED"
        ? { kind: "unknown", text: "接続未確認" }
        : connection.state === "CONNECTING"
          ? { kind: "pending", text: "接続を確認中…" }
          : connection.authenticated
            ? { kind: "success", text: "接続確認済み" }
            : connection.state === "AUTH_FAILED"
              ? { kind: "error", text: "認証を確認してください" }
              : {
                  kind: "error",
                  text: connection.reachable
                    ? "接続先の設定を確認してください"
                    : "サーバーに接続できません",
                };
  return (
    <small className="connection-status" data-state={status.kind} role="status">
      <span className="connection-dot" aria-hidden="true" />
      {status.text}
    </small>
  );
}
