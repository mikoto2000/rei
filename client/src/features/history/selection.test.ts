import { it, expect, vi } from "vitest";
import { SessionSelection } from "./selection";
import type { Command } from "../../tauri/commands";
import type { Conversation } from "../../entities/models";
const c: Conversation = {
  localId: "c",
  serverProfileId: "server",
  projectId: "locked",
  sessionId: "s",
  title: "Server title",
  createdAt: 0,
  lastAccessedAt: 0,
};
it("opens a remote session via application boundary and leaves project authoritative", async () => {
  const call = vi.fn().mockResolvedValue(c);
  const selection = new SessionSelection(call as Command);
  expect(await selection.open("server", "s")).toEqual(c);
  expect(call).toHaveBeenCalledWith("session_open", {
    serverId: "server",
    sessionId: "s",
  });
  expect(selection.snapshot()).toMatchObject({
    conversation: c,
    loading: false,
    error: null,
  });
});
it("404 is a selection error, never a new conversation", async () => {
  const call = vi.fn().mockRejectedValue("SessionNotFound");
  const selection = new SessionSelection(call as Command);
  expect(await selection.open("server", "gone")).toBeNull();
  expect(selection.snapshot()).toMatchObject({
    conversation: null,
    loading: false,
    error: "SessionNotFound",
  });
  expect(call).toHaveBeenCalledTimes(1);
});
it("back navigation invalidates a pending open and never exposes unexpected error text", async () => {
  let resolve!: (c: Conversation) => void;
  const call = vi.fn(
    () =>
      new Promise<Conversation>((r) => {
        resolve = r;
      }),
  );
  const selection = new SessionSelection(call as Command);
  const opening = selection.open("server", "s");
  expect(selection.snapshot().loading).toBe(true);
  selection.clear();
  resolve(c);
  expect(await opening).toBeNull();
  expect(selection.snapshot().conversation).toBeNull();
  const bad = new SessionSelection(
    vi.fn().mockRejectedValue(new Error("secret")) as Command,
  );
  await bad.open("s", "s");
  expect(bad.snapshot().error).toBe("UnexpectedServerError");
});
