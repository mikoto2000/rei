import type {
  WorkspaceOperation,
  WorkspaceResult,
} from "../src/entities/workspace";
type Item = WorkspaceResult["items"][number];
let revision = 1;
let text =
  "公開用に登録した話題から作った投稿案です。\n本文の全文を確認してください。";
let state = "PENDING_REVIEW";
let noticeRead = false;
let rejectionReason = "";
let feedback: Item | null = null;
const item = (): Item => ({
  id: "draft-one",
  title: "投稿案",
  fields: [
    ["Type", "AutonomousDraft"],
    ["Text", text],
    ["Revision", String(revision)],
    ["Status", state],
    ["Account", "rei.example"],
    ["DID", "did:plc:fixture"],
    ["Reason", "公開話題の共有"],
    ["SourceIds", "topic:source-one:1"],
    ["CreatedAt", "2026-11-28T03:00:00Z"],
    ["UpdatedAt", "2026-11-28T03:00:00Z"],
    ["ExpiresAt", "2026-12-01T03:00:00Z"],
    ["Trigger", "MANUAL"],
    ["RegenerationAttempts", "0"],
    ["RejectionReason", rejectionReason],
  ],
});

const source = (revision: number, text: string, allowed: boolean): Item => ({
  id: "feedback-one",
  title: "公開用資料",
  fields: [
    ["Type", "AutonomousSource"],
    ["Revision", String(revision)],
    ["Text", text],
    ["PublicUseAllowed", String(allowed)],
    ["UpdatedAt", "2026-11-28T03:00:00Z"],
  ],
});
export function autonomousFixture(op: WorkspaceOperation): WorkspaceResult {
  switch (op.operation) {
    case "autonomousPublished":
      return {
        title: "公開済み履歴",
        items: [
          {
            id: "published-one",
            title: "公開済み",
            fields: [
              ["Type", "AutonomousDraft"],
              ["Status", "PUBLISHED"],
              ["Text", "以前に公開した話題です。\nこちらは履歴の本文です。"],
              ["Account", "rei.example"],
              ["DID", "did:plc:fixture"],
              ["UpdatedAt", "2026-11-27T03:00:00Z"],
              ["URI", "at://did:plc:fixture/app.bsky.feed.post/aaaaaaaaaaaaa"],
              [
                "CID",
                "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a",
              ],
            ],
          },
        ],
      };
    case "autonomousHistory":
      return {
        title: "生成履歴",
        items: [
          {
            id: "manual:first",
            title: "生成",
            fields: [
              ["Type", "AutonomousHistory"],
              ["Status", "GENERATED"],
              ["Trigger", "MANUAL"],
              ["Kind", "NEW"],
              ["Reason", "投稿案を作成しました。"],
              ["DraftId", "draft-one"],
              ["CreatedAt", "2026-11-28T03:00:00Z"],
            ],
          },
          {
            id: "scheduled:skip",
            title: "見送り",
            fields: [
              ["Type", "AutonomousHistory"],
              ["Status", "SKIPPED"],
              ["Trigger", "SCHEDULED"],
              ["Kind", "NEW"],
              ["Reason", "duplicate_publication"],
              ["CreatedAt", "2026-11-28T04:00:00Z"],
            ],
          },
        ],
      };
    case "autonomousNotifications":
      return {
        title: "投稿案の通知",
        items:
          noticeRead || state !== "PENDING_REVIEW"
            ? []
            : [
                {
                  id: "a8203063-d97c-4e26-a601-745217152952",
                  title: "投稿案の確認待ち",
                  fields: [
                    ["Type", "AutonomousNotice"],
                    ["DraftId", "draft-one"],
                    ["Status", "OPEN"],
                    ["CreatedAt", "2026-11-28T03:00:00Z"],
                  ],
                },
              ],
      };
    case "autonomousNoticeAck":
      if (op.id !== "a8203063-d97c-4e26-a601-745217152952")
        throw "InvalidInput";
      noticeRead = true;
      return { title: "既読", items: [] };
    case "autonomousSources":
      return { title: "話題", items: feedback ? [feedback] : [] };
    case "autonomousAddSource":
      feedback = source(1, op.text, op.publicUseAllowed);
      return { title: "資料を登録", items: [feedback] };
    case "autonomousSource":
      if (!feedback || op.id !== feedback.id) throw "NotFound";
      return { title: "資料", items: [feedback] };
    case "autonomousPermitSource":
    case "autonomousRevokeSource": {
      if (
        !feedback ||
        op.id !== feedback.id ||
        Number(feedback.fields.find(([name]) => name === "Revision")?.[1]) !==
          op.expectedRevision
      )
        throw "ResourceConflict";
      feedback = source(
        op.expectedRevision + 1,
        feedback.fields.find(([name]) => name === "Text")?.[1] ?? "",
        op.operation === "autonomousPermitSource",
      );
      return { title: "資料の利用許可", items: [feedback] };
    }
    case "autonomousDrafts":
    case "autonomousDraft":
      break;
    case "autonomousEdit":
      if (op.expectedRevision !== revision || state !== "PENDING_REVIEW")
        throw "ResourceConflict";
      revision++;
      text = op.text;
      break;
    case "autonomousReject":
      if (op.expectedRevision !== revision) throw "ResourceConflict";
      state = "REJECTED";
      rejectionReason = op.reason;
      break;
    case "autonomousRegenerate":
      if (op.expectedRevision !== revision) throw "ResourceConflict";
      revision++;
      text = "再生成された本文の全文です。\n変更された内容を確認してください。";
      return {
        title: "再生成結果",
        items: [
          {
            id: null,
            title: "生成結果",
            fields: [
              ["Type", "AutonomousGeneration"],
              ["GenerationStatus", "DRAFT_REGENERATED"],
              ["GenerationReason", "generated"],
            ],
          },
          item(),
        ],
      };
    case "autonomousPublish":
      if (op.expectedRevision !== revision || state !== "PENDING_REVIEW")
        throw "ResourceConflict";
      state = new URLSearchParams(window.location.search).has(
        "unknown-publication",
      )
        ? "UNKNOWN"
        : "PUBLISHED";
      break;
    case "autonomousReconcile":
      if (op.expectedRevision !== revision || state !== "UNKNOWN")
        throw "ResourceConflict";
      state = "PUBLISHED";
      break;
    default:
      throw "InvalidInput";
  }
  return { title: "投稿案", items: [item()] };
}
