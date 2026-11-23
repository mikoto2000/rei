import { useMemo, useState } from "react";
import type { Command } from "../../tauri/commands";
import type { Conversation, Project, Run } from "../../entities/models";
import { Attention } from "../attention/Attention";
import { Recovery } from "../recovery/Recovery";
import { HumanAnswers } from "../recovery/HumanAnswers";

export function ConversationControls({
  call,
  conversation,
  projects,
  runs,
  onAccepted,
  onOpenRun,
}: {
  call: Command;
  conversation: Conversation;
  projects: Project[];
  runs: Run[];
  onAccepted: (run: Run) => void;
  onOpenRun: (run: Run) => void;
}) {
  const [panel, setPanel] = useState<"attention" | "recovery" | null>(null);
  const scopedProjects = useMemo(
    () => projects.filter((p) => p.id === conversation.projectId),
    [projects, conversation.projectId],
  );
  const waiting = runs.filter(
    (r) =>
      r.conversationId === conversation.localId &&
      (r.status === "WAITING" || r.status === "WAITING_APPROVAL"),
  );
  return (
    <section className="conversation-controls" aria-label="この会話の操作">
      {waiting.length > 0 && (
        <p role="status">
          確認待ちの作業が{waiting.length}
          件あります。承認・回答待ちを確認してください。
        </p>
      )}
      <button
        aria-expanded={panel === "attention"}
        onClick={() => setPanel(panel === "attention" ? null : "attention")}
      >
        この会話の承認・回答待ち
      </button>
      <button
        aria-expanded={panel === "recovery"}
        onClick={() => setPanel(panel === "recovery" ? null : "recovery")}
      >
        この会話の復旧・再開
      </button>
      {panel && (
        <button onClick={() => setPanel(null)}>会話の操作を閉じる</button>
      )}
      {panel === "attention" && (
        <>
          <Attention
            call={call}
            serverId={conversation.serverProfileId}
            projects={scopedProjects}
            sessionId={conversation.sessionId ?? undefined}
          />
          <HumanAnswers
            call={call}
            serverId={conversation.serverProfileId}
            projectId={conversation.projectId}
            sessionId={conversation.sessionId ?? undefined}
          />
        </>
      )}
      {panel === "recovery" && (
        <Recovery
          call={call}
          serverId={conversation.serverProfileId}
          projects={scopedProjects}
          sessionId={conversation.sessionId ?? undefined}
          onAccepted={onAccepted}
          onOpenRun={onOpenRun}
        />
      )}
    </section>
  );
}
