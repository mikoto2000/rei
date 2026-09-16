export type RunStatus =
  "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
export type StreamState =
  "CONNECTING" | "CONNECTED" | "RECONNECTING" | "CLOSED";
export type ConnectionState =
  | "DISCONNECTED"
  | "CONNECTING"
  | "CONNECTED"
  | "AUTH_FAILED"
  | "SERVER_UNREACHABLE";
export interface Server {
  id: string;
  name: string;
  baseUrl: string;
  hasCredential: boolean;
}
export interface Project {
  id: string;
  name: string;
  path: string;
}
export interface Conversation {
  localId: string;
  serverProfileId: string;
  projectId: string;
  sessionId: string | null;
  title: string;
  createdAt: number;
  lastAccessedAt: number;
}
export interface Connection {
  serverId: string;
  state: ConnectionState;
  reachable: boolean;
  authenticated: boolean;
  error: string | null;
}
export interface Run {
  serverId: string;
  conversationId: string;
  projectId: string;
  runId: string;
  sessionId: string;
  turnId: string;
  prompt: string;
  status: RunStatus;
  streamState: StreamState;
  assistantText: string;
  lastSequence: string | null;
  incomplete: boolean;
  error: string | null;
  failure: string | null;
  tools: { id: string; name: string; status: string; summary: string }[];
  workingSet: { id: string; kind: string; identifier: string; path: string }[];
  revision: number;
}
export interface Snapshot {
  servers: Server[];
  selectedServer: string | null;
  conversations: Conversation[];
  runs: Run[];
  unlocked: boolean;
  notifications: boolean;
}
export const activeRuns = (runs: Run[]) =>
  runs.filter((r) => r.status === "QUEUED" || r.status === "RUNNING");
export function mergeRun(runs: Run[], incoming: Run): Run[] {
  const index = runs.findIndex(
    (r) => r.serverId === incoming.serverId && r.runId === incoming.runId,
  );
  if (index < 0) return [...runs, incoming];
  if (runs[index].revision > incoming.revision) return runs;
  return runs.map((r, i) => (i === index ? incoming : r));
}
export const canSubmit = (
  conversation: string,
  message: string,
  runs: Run[],
  pending: boolean,
) =>
  !!message.trim() &&
  !pending &&
  !activeRuns(runs).some((r) => r.conversationId === conversation);
const errors: Record<string, string> = {
  ProjectNotFound:
    "プロジェクトがサーバーに存在しません。プロジェクト一覧を再取得してください。",
  NotificationDenied:
    "OS の通知権限が許可されませんでした。Run は引き続き実行されます。",
  SessionNotFound: "サーバー上でこの会話セッションが存在しません。",
  SessionProjectConflict:
    "この会話は元のサーバー・プロジェクトに固定されています。新しい会話を作成してください。",
  AuthenticationFailed: "API Key を確認してください。",
  VaultLocked:
    "Vault を解錠してください。パスフレーズが違う場合も解錠できません。",
  ServerUnreachable:
    "サーバーに接続できません。URL とネットワークを確認してください。",
  ReplayGap: "イベント履歴が欠けています。Run の状態を回収しています。",
  StreamDisconnected: "ストリームが切断されました。再接続しています。",
  RunNotFound: "Run がサーバーに存在しません。",
  Storage: "保存に失敗しました。空き容量とアクセス権を確認してください。",
  Busy: "実行中の処理があります。終了後に操作してください。",
  InvalidInput:
    "入力内容を確認してください。Vault パスフレーズは12文字以上必要です。",
  InvalidResponse: "サーバーから想定外の応答がありました。",
  NotFound: "対象が見つかりません。",
};
export const errorText = (error: unknown) =>
  typeof error === "string" && errors[error]
    ? errors[error]
    : "操作に失敗しました。設定と接続を確認してください。";
