import { render, screen, cleanup } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { Chat } from "./Chat";
afterEach(cleanup);
it("restores an assistant-only notification without an empty user bubble", () => {
  const { container } = render(
    <Chat
      conversation={{
        localId: "c",
        serverProfileId: "s",
        projectId: "p",
        sessionId: "session",
        title: "chat",
        createdAt: 0,
        lastAccessedAt: 0,
      }}
      projectName="rei"
      runs={[]}
      pending={false}
      error={null}
      onSend={vi.fn()}
      onStop={vi.fn()}
      onContinue={vi.fn()}
      onRefresh={vi.fn()}
      onSubscribe={vi.fn()}
      history={{
        items: [
          {
            turnId: "behavior:b1",
            runId: "behavior:b1",
            userMessage: "",
            assistantMessage: "そろそろ戻ろう。",
            createdAt: "2026-09-25T12:00:00Z",
            source: "BEHAVIOR_NOTIFICATION",
            sourceId: "b1",
            metadata: { severity: "WARNING" },
          },
        ],
        nextCursor: null,
        status: "LOADED",
        loadingInitial: false,
        loadingMore: false,
        refreshing: false,
        error: null,
        stale: false,
      }}
    />,
  );
  expect(screen.getAllByText("そろそろ戻ろう。")).toHaveLength(1);
  expect(container.querySelector(".message.user")).toBeNull();
  expect(container.querySelector(".message.assistant")?.textContent).toContain(
    "REI",
  );
});
