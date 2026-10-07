import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { Artifacts } from "./Artifacts";
afterEach(cleanup);
const item = {
  artifactId: "id",
  owner: "RUN",
  projectId: "p",
  sessionId: "session",
  runId: "run",
  taskId: "run:run",
  mediaType: "text/plain",
  filename: "結果.txt",
  size: 4,
  sha256: "a".repeat(64),
  createdAt: "2026-10-07T01:00:00Z",
  expiresAt: null,
  storageReference: "artifact:id",
  status: "AVAILABLE",
};
it("previews escaped text and saves only after an explicit owner-scoped action", async () => {
  const call = vi.fn().mockImplementation((name) =>
    Promise.resolve(
      name === "artifacts_list"
        ? { items: [item], nextCursor: null }
        : name === "artifact_preview"
          ? {
              artifact: item,
              text: "<script>unsafe()</script>",
              dataUrl: null,
            }
          : {
              path: "Downloads/Rei/id/結果.txt",
              size: 4,
              sha256: item.sha256,
            },
    ),
  );
  render(<Artifacts call={call as Command} serverId="s" projects={[]} />);
  await userEvent.click(await screen.findByRole("button", { name: "preview" }));
  expect(await screen.findByText("<script>unsafe()</script>")).toBeTruthy();
  expect(document.querySelector("script")).toBeNull();
  expect(call.mock.calls.some(([name]) => name === "artifact_save")).toBe(
    false,
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Downloadsへ保存" }),
  );
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("artifact_save", {
      serverId: "s",
      projectId: "p",
      sessionId: "session",
      artifactId: "id",
    }),
  );
  expect(await screen.findByText(/Downloads\/Rei/)).toBeTruthy();
});
it("keeps expired results visible and disables content operations", async () => {
  const call = vi.fn().mockResolvedValue({
    items: [{ ...item, status: "EXPIRED" }],
    nextCursor: null,
  });
  render(<Artifacts call={call as Command} serverId="s" projects={[]} />);
  expect(await screen.findByText("EXPIRED")).toBeTruthy();
  expect(
    (
      screen.getByRole("button", {
        name: "Downloadsへ保存",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  expect(
    (screen.getByRole("button", { name: "preview" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});
it("discards a previous server response after switching servers", async () => {
  let resolveOld!: (value: unknown) => void;
  const old = new Promise((resolve) => {
    resolveOld = resolve;
  });
  const call = vi.fn().mockImplementation((_name, args) =>
    args.serverId === "old"
      ? old
      : Promise.resolve({
          items: [{ ...item, filename: "new.txt" }],
          nextCursor: null,
        }),
  );
  const view = render(
    <Artifacts call={call as Command} serverId="old" projects={[]} />,
  );
  view.rerender(
    <Artifacts call={call as Command} serverId="new" projects={[]} />,
  );
  expect(await screen.findByText("new.txt")).toBeTruthy();
  resolveOld({ items: [item], nextCursor: null });
  await waitFor(() => expect(screen.queryByText(item.filename)).toBeNull());
});
it("opens a Task artifact through its exact project and session identity", async () => {
  const call = vi.fn().mockResolvedValue(item);
  render(
    <Artifacts
      call={call as Command}
      serverId="s"
      projects={[]}
      selection={{
        projectId: "p",
        sessionId: "session",
        runId: null,
        artifactId: "id",
      }}
    />,
  );
  expect(await screen.findByText(item.filename)).toBeTruthy();
  expect(call).toHaveBeenCalledWith("artifact_get", {
    serverId: "s",
    projectId: "p",
    sessionId: "session",
    artifactId: "id",
  });
  expect(call.mock.calls.some(([name]) => name === "artifacts_list")).toBe(
    false,
  );
});
it("reports an image decode failure without leaving a broken preview", async () => {
  const image = { ...item, mediaType: "image/png" };
  const call = vi.fn().mockImplementation((name) =>
    Promise.resolve(
      name === "artifacts_list"
        ? { items: [image], nextCursor: null }
        : {
            artifact: image,
            text: null,
            dataUrl: "data:image/png;base64,broken",
          },
    ),
  );
  render(<Artifacts call={call as Command} serverId="s" projects={[]} />);
  await userEvent.click(await screen.findByRole("button", { name: "preview" }));
  fireEvent.error(await screen.findByRole("img"));
  expect(await screen.findByRole("alert")).toBeTruthy();
  expect(screen.queryByRole("img")).toBeNull();
});
it("clears previously previewed content when a subsequent read fails", async () => {
  let reads = 0;
  const call = vi.fn().mockImplementation((name) => {
    if (name === "artifacts_list")
      return Promise.resolve({ items: [item], nextCursor: null });
    if (++reads === 1)
      return Promise.resolve({
        artifact: item,
        text: "previous content",
        dataUrl: null,
      });
    return Promise.reject("NotFound");
  });
  render(<Artifacts call={call as Command} serverId="s" projects={[]} />);
  await userEvent.click(await screen.findByRole("button", { name: "preview" }));
  expect(await screen.findByText("previous content")).toBeTruthy();
  await userEvent.click(screen.getByRole("button", { name: "preview" }));
  expect(await screen.findByRole("alert")).toBeTruthy();
  expect(screen.queryByText("previous content")).toBeNull();
});
