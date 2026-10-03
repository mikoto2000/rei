import type {
  WorkspaceOperation,
  BackgroundOperation,
} from "../../entities/workspace";
type Field = {
  key: string;
  label: string;
  default?: string;
  optional?: boolean;
  options?: string[];
};
type Operation = { id: string; label: string; button: string; fields: Field[] };
const id: Field = { key: "id", label: "ID" };
const url: Field = { key: "url", label: "URL" };
const displayName: Field = {
  key: "displayName",
  label: "表示名",
  optional: true,
};
const read = (key: string, label: string, fields: Field[] = []): Operation => ({
  id: key,
  label,
  button: "取得",
  fields,
});
const create = (key: string, label: string, fields: Field[]): Operation => ({
  id: key,
  label,
  button: "作成",
  fields,
});
const remove = (key: string, label: string): Operation => ({
  id: key,
  label,
  button: "削除",
  fields: [id],
});
export const operations: Operation[] = [
  read("feeds", "Feed 一覧"),
  read("feed", "Feed 詳細", [id]),
  create("createFeed", "Feed 追加", [url, displayName]),
  {
    id: "updateFeed",
    label: "Feed 更新",
    button: "更新",
    fields: [
      id,
      displayName,
      {
        key: "enabled",
        label: "有効",
        optional: true,
        options: ["", "true", "false"],
      },
    ],
  },
  remove("deleteFeed", "Feed 削除"),
  read("skills", "Skills 一覧"),
  read("skill", "Skill 詳細", [{ key: "name", label: "Skill 名" }]),
  { id: "reloadSkills", label: "Skills 再読込", button: "再読込", fields: [] },
  read("profile", "Profile 統計"),
  read("briefing", "Briefing"),
  read("search", "Search", [
    { key: "query", label: "検索語" },
    { key: "vectorTopK", label: "Vector 件数", default: "3" },
    { key: "webTopK", label: "Web 件数", default: "5" },
    { key: "threshold", label: "Threshold", default: "0.5" },
  ]),
  read("reminders", "Reminders 一覧"),
  read("reminder", "Reminder 詳細", [id]),
  create("createReminder", "Reminder 作成", [
    { key: "message", label: "メッセージ" },
    {
      key: "at",
      label: "通知日時 (例: 2026-10-04T09:00:00+09:00)",
      optional: true,
    },
    { key: "target", label: "対象日時 (at の代わり)", optional: true },
    { key: "minutesBefore", label: "何分前", optional: true },
  ]),
  remove("deleteReminder", "Reminder 削除"),
  read("interests", "Interests 一覧", [
    { key: "hours", label: "過去の時間数", default: "24" },
  ]),
  create("createInterest", "Interest 保存", [
    { key: "topic", label: "話題" },
    { key: "reason", label: "理由" },
    { key: "searchQuery", label: "検索語" },
    { key: "summary", label: "要約" },
    { key: "sourceUrls", label: "参照 URL (1行に1件)", optional: true },
  ]),
  read("memories", "Memories 一覧"),
  read("memory", "Memory 詳細", [id]),
  create("createMemory", "Memory 作成", [
    { key: "content", label: "内容" },
    {
      key: "memoryType",
      label: "種類",
      default: "FACT",
      options: [
        "FACT",
        "PREFERENCE",
        "CONSTRAINT",
        "PROJECT_STATE",
        "PROCEDURE",
        "LESSON",
        "RELATION",
        "USER_PREFERENCE",
        "PROJECT_CONTEXT",
        "DECISION",
        "TASK",
        "KNOWLEDGE",
        "EPISODE_SUMMARY",
        "TEMPORARY_CONTEXT",
      ],
    },
    {
      key: "scope",
      label: "Scope",
      default: "GLOBAL",
      options: ["GLOBAL", "SHORT_TERM", "LONG_TERM", "PERMANENT"],
    },
    { key: "confidence", label: "Confidence", default: "0.8" },
  ]),
  remove("deleteMemory", "Memory 削除"),
  { id: "summary", label: "URL の要約", button: "開始", fields: [url] },
  {
    id: "image",
    label: "画像生成",
    button: "開始",
    fields: [
      { key: "prompt", label: "プロンプト" },
      { key: "size", label: "サイズ (幅x高さ)", optional: true },
    ],
  },
];
export function buildOperation(
  operation: string,
  v: Record<string, string>,
): WorkspaceOperation | BackgroundOperation {
  const spec = operations.find((s) => s.id === operation);
  if (!spec || spec.fields.some((f) => !f.optional && !v[f.key]?.trim()))
    throw "InvalidInput";
  const optional = (key: string) => v[key]?.trim() || null;
  const number = (key: string) => {
    const n = Number(v[key]);
    if (!Number.isFinite(n)) throw "InvalidInput";
    if (!["threshold", "confidence"].includes(key) && !Number.isSafeInteger(n))
      throw "InvalidInput";
    return n;
  };
  switch (operation) {
    case "feed":
    case "reminder":
    case "deleteFeed":
    case "deleteReminder":
      return { operation, id: number("id") };
    case "skill":
      return { operation, name: v.name };
    case "memory":
    case "deleteMemory":
      return { operation, id: v.id };
    case "search":
      return {
        operation,
        query: v.query,
        vectorTopK: number("vectorTopK"),
        webTopK: number("webTopK"),
        threshold: number("threshold"),
      };
    case "createFeed":
      return { operation, url: v.url, displayName: optional("displayName") };
    case "updateFeed":
      return {
        operation,
        id: number("id"),
        displayName: optional("displayName"),
        enabled: optional("enabled") === null ? null : v.enabled === "true",
      };
    case "createReminder":
      return {
        operation,
        message: v.message,
        at: optional("at"),
        target: optional("target"),
        minutesBefore:
          optional("minutesBefore") === null ? null : number("minutesBefore"),
      };
    case "interests":
      return { operation, hours: number("hours") };
    case "createInterest":
      return {
        operation,
        topic: v.topic,
        reason: v.reason,
        searchQuery: v.searchQuery,
        summary: v.summary,
        sourceUrls: (v.sourceUrls || "")
          .split("\n")
          .map((s) => s.trim())
          .filter(Boolean),
      };
    case "createMemory":
      return {
        operation,
        content: v.content,
        memoryType: v.memoryType,
        scope: v.scope,
        confidence: number("confidence"),
      };
    case "summary":
      return { operation, url: v.url };
    case "image":
      return { operation, prompt: v.prompt, size: optional("size") };
    case "feeds":
    case "skills":
    case "profile":
    case "briefing":
    case "reminders":
    case "memories":
    case "reloadSkills":
      return { operation };
    default:
      throw "InvalidInput";
  }
}
