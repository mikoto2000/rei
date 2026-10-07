import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
} from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { Workspace } from "./Workspace";
afterEach(cleanup);
it("opens generated artifacts using the background Run ownership rather than result text", () => {
  const open = vi.fn();
  const run = {
    serverId: "server",
    projectId: "p",
    runId: "image-run",
    sessionId: null,
    conversationId: null,
    prompt: "Image",
    status: "COMPLETED",
    streamState: "CLOSED",
    assistantText: "untrusted filename or Artifact: fake",
    tools: [],
    activities: [],
    messages: [],
  } as unknown as import("../../entities/models").Run;
  render(
    <Workspace
      call={vi.fn()}
      serverId="server"
      projects={[]}
      runs={[run]}
      onAccepted={vi.fn()}
      onArtifacts={open}
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "このRunの生成物" }));
  expect(open).toHaveBeenCalledWith({
    projectId: "p",
    sessionId: null,
    runId: "image-run",
  });
});
it("uses typed commands for reads, writes, and project-owned background runs", async () => {
  const call = vi.fn().mockResolvedValue({
    title: "Feed",
    items: [
      { id: "1", title: "News", fields: [["URL", "https://example.com"]] },
    ],
  });
  const accepted = vi.fn();
  render(
    <Workspace
      call={call}
      serverId="server"
      projects={[{ id: "p", name: "rei", path: "/server/path" }]}
      runs={[]}
      onAccepted={accepted}
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "取得" }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("workspace_execute", {
      serverId: "server",
      operation: { operation: "feeds" },
    }),
  );
  expect(await screen.findByText("News")).toBeTruthy();
  fireEvent.change(screen.getByLabelText("操作"), {
    target: { value: "createFeed" },
  });
  fireEvent.change(screen.getByLabelText("URL"), {
    target: { value: "https://example.com/rss" },
  });
  fireEvent.change(screen.getByLabelText("表示名"), {
    target: { value: "News" },
  });
  fireEvent.click(screen.getByRole("button", { name: "作成" }));
  await waitFor(() =>
    expect(call).toHaveBeenLastCalledWith("workspace_execute", {
      serverId: "server",
      operation: {
        operation: "createFeed",
        url: "https://example.com/rss",
        displayName: "News",
      },
    }),
  );
  fireEvent.change(screen.getByLabelText("操作"), {
    target: { value: "summary" },
  });
  fireEvent.change(screen.getByLabelText("URL"), {
    target: { value: "https://example.com" },
  });
  const run = {
    runId: "r",
    serverId: "server",
    projectId: "p",
    status: "QUEUED",
  };
  call.mockResolvedValueOnce(run);
  fireEvent.click(screen.getByRole("button", { name: "開始" }));
  await waitFor(() =>
    expect(call).toHaveBeenLastCalledWith("background_submit", {
      serverId: "server",
      projectId: "p",
      operation: { operation: "summary", url: "https://example.com" },
    }),
  );
  expect(accepted).toHaveBeenCalledWith(run);
  expect(screen.queryByLabelText("ファイルパス")).toBeNull();
});
it("shows safe conflict errors and does not submit missing input", async () => {
  const call = vi.fn().mockRejectedValue("Conflict");
  render(
    <Workspace
      call={call}
      serverId="server"
      projects={[]}
      runs={[]}
      onAccepted={vi.fn()}
    />,
  );
  fireEvent.change(screen.getByLabelText("操作"), {
    target: { value: "createMemory" },
  });
  fireEvent.click(screen.getByRole("button", { name: "作成" }));
  expect(call).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("内容"), {
    target: { value: "Remember" },
  });
  fireEvent.click(screen.getByRole("button", { name: "作成" }));
  expect((await screen.findByRole("alert")).textContent).toContain("競合");
});
