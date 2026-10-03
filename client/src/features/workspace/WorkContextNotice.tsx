import { useEffect, useState } from "react";
import type { Command } from "../../tauri/commands";

export function WorkContextNotice({
  call,
  serverId,
  projectId,
  conversationId,
  seen,
}: {
  call: Command;
  serverId: string;
  projectId: string;
  conversationId: string;
  seen: Set<string>;
}) {
  const [text, setText] = useState<string | null>(null);
  useEffect(() => {
    let disposed = false;
    const key = JSON.stringify([serverId, projectId, conversationId]);
    setText(null);
    if (seen.has(key)) return;
    void call("workspace_execute", {
      serverId,
      operation: { operation: "workContext", projectId },
    })
      .then((result) => {
        if (disposed) return;
        const fields = result.items[0]?.fields ?? [];
        seen.add(key);
        if (fields.find(([label]) => label === "Auto present")?.[1] === "true")
          setText(fields.find(([label]) => label === "引き継ぎ")?.[1] ?? null);
      })
      .catch(() => {
        /* Handoff availability must not prevent chat on older/offline servers. */
      });
    return () => {
      disposed = true;
    };
  }, [call, serverId, projectId, conversationId, seen]);
  return text ? (
    <aside className="card" aria-label="プロジェクトの引き継ぎ">
      <h2>Work Context</h2>
      <p style={{ whiteSpace: "pre-wrap" }}>{text}</p>
    </aside>
  ) : null;
}
