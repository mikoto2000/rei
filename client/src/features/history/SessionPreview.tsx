import { useEffect, useState } from "react";
import type { Command } from "../../tauri/commands";
import type { TurnPage } from "../../entities/models";
import { errorText } from "../../entities/models";
export function SessionPreview({
  call,
  serverId,
  sessionId,
  updatedAt,
}: {
  call: Command;
  serverId: string;
  sessionId: string;
  updatedAt: string;
}) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let disposed = false;
    if (!open) return;
    setText(null);
    setError(null);
    void (async () => {
      let cursor: string | null = null;
      let latest = "この会話にはまだメッセージがありません。";
      const seen = new Set<string>();
      do {
        const page: TurnPage = await call("session_turns", {
          serverId,
          sessionId,
          limit: 100,
          cursor,
        });
        if (disposed) return;
        for (const turn of page.items)
          latest = turn.assistantMessage || turn.userMessage || latest;
        cursor = page.nextCursor;
        if (cursor && seen.has(cursor)) throw "InvalidCursor";
        if (cursor) seen.add(cursor);
      } while (cursor && !disposed);
      if (!disposed) setText(latest);
    })().catch((error) => {
      if (!disposed) setError(errorText(error));
    });
    return () => {
      disposed = true;
    };
  }, [call, serverId, sessionId, updatedAt, open]);
  return (
    <div className="session-preview">
      <button
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        {open ? "プレビューを閉じる" : "最新の発言を確認"}
      </button>
      {open && (
        <p role={error ? "alert" : "status"}>
          {error ??
            (text
              ? text.slice(0, 300) + (text.length > 300 ? "…" : "")
              : "最新の発言を読み込んでいます…")}
        </p>
      )}
    </div>
  );
}
