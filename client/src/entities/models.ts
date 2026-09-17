export type RunStatus =
  "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
export type StreamState =
  "CONNECTING" | "CONNECTED" | "RECONNECTING" | "CLOSED";
export type ConnectionState =
  | "DISCONNECTED"
  | "CONNECTING"
  | "CONNECTED"
  | "AUTH_FAILED"
  | "CONNECTION_FAILED"
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
export interface SessionSummary {
  sessionId: string;
  projectId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}
export interface ConversationTurn {
  turnId: string;
  runId: string;
  userMessage: string;
  assistantMessage: string | null;
  createdAt: string;
}
export interface SessionPage {
  items: SessionSummary[];
  nextCursor: string | null;
}
export interface TurnPage {
  items: ConversationTurn[];
  nextCursor: string | null;
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
  timeline?: TimelineEntry[];
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
  tools: ToolExecution[];
  activities: Activity[];
  messages: {
    messageId: string;
    role: string;
    text: string;
    completed: boolean;
  }[];
  workingSet: { id: string; kind: string; identifier: string; path: string }[];
  revision: number;
}
export type TimelineEntry =
  | { kind: "text"; id: string; messageId: string; text: string }
  | { kind: "tool"; id: string; tool: ToolExecution }
  | { kind: "activity"; id: string; activity: Activity };
export interface ActivityError {
  type: string;
  message: string;
}
export interface ToolExecution {
  id: string;
  name: string;
  status: string;
  summary: string;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs?: number | null;
  error?: ActivityError | null;
}
export interface Activity {
  id: string;
  category: string;
  label: string;
  status: string;
  summary: string;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  firstTokenMs: number | null;
  error: ActivityError | null;
  metrics: { label: string; value: number }[];
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
  InvalidCursor: "ページ情報が無効です。一覧を更新してください。",
  InvalidLimit: "取得件数は1〜100件で指定してください。",
  UnexpectedServerError:
    "サーバーでエラーが発生しました。時間をおいて再試行してください。",
  ProjectNotFound:
    "プロジェクトがサーバーに存在しません。プロジェクト一覧を再取得してください。",
  NotificationDenied:
    "OS の通知権限が許可されませんでした。Run は引き続き実行されます。",
  SessionNotFound: "サーバー上でこの会話セッションが存在しません。",
  SessionProjectConflict:
    "この会話は元のサーバー・プロジェクトに固定されています。新しい会話を作成してください。",
  AuthenticationFailed: "API Key を確認してください。",
  HealthAuthenticationRequired:
    "ヘルスチェックが認証・権限エラーになりました。接続先 URL と、サーバーの /actuator/health を認証なしで取得できる設定を確認してください。",
  PermissionDenied:
    "アクセスが拒否されました（HTTP 403）。API Key とサーバーのアクセス権限を確認してください。",
  EndpointNotFound:
    "API の接続先が見つかりません（HTTP 404）。URL とサーバーの API 設定を確認してください。",
  HttpRedirect:
    "サーバーからリダイレクトが返されました。転送先の正しい URL を設定してください。",
  RequestRejected:
    "サーバーがリクエストを受け付けませんでした。入力内容と API の互換性を確認してください。",
  RateLimited:
    "リクエストが多すぎます（HTTP 429）。時間をおいて再試行してください。",
  RequestTimeout:
    "通信がタイムアウトしました。接続先とネットワークを確認し、再試行してください。",
  VaultLocked:
    "Vault を解錠してください。パスフレーズが違う場合も解錠できません。",
  ServerUnreachable:
    "サーバーとの通信に失敗しました。URL・ネットワーク・HTTPS の場合は証明書を確認してください。",
  ReplayGap: "イベント履歴が欠けています。Run の状態を回収しています。",
  StreamDisconnected: "ストリームが切断されました。再接続しています。",
  RunNotFound: "Run がサーバーに存在しません。",
  Storage: "保存に失敗しました。空き容量とアクセス権を確認してください。",
  Busy: "実行中の処理があります。終了後に操作してください。",
  InvalidInput: "入力内容と必須項目を確認してください。",
  InvalidServerUrl:
    "接続先 URL が無効です。http:// または https:// で始まる URL を指定してください。認証情報・クエリ・フラグメントは指定できません。",
  InvalidCredential:
    "API Key の形式が無効です。空白のみの値や改行は使用できません。",
  InvalidPassphrase: "Vault パスフレーズは12文字以上で入力してください。",
  InvalidResponse: "サーバーから想定外の応答がありました。",
  NotFound: "対象が見つかりません。",
};
export const errorText = (error: unknown) =>
  typeof error === "string" && errors[error]
    ? errors[error]
    : "操作に失敗しました。設定と接続を確認してください。";
