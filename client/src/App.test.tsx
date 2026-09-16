import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
} from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { App } from "./App";
import type { Command } from "./tauri/commands";
import type { Events } from "./tauri/events";
import type { Snapshot } from "./entities/models";
afterEach(cleanup);
const empty: Snapshot = {
  servers: [],
  selectedServer: null,
  conversations: [],
  runs: [],
  unlocked: false,
  notifications: false,
};
const events: Events = {
  runs: async () => () => {},
  connection: async () => () => {},
};
it("starts with native vault setup when unconfigured", async () => {
  const call = vi.fn(async () => empty) as unknown as Command;
  render(<App call={call} subscriptions={events} />);
  expect(await screen.findByText("Credential Vault")).toBeTruthy();
  expect(screen.getByLabelText("API Key")).toHaveProperty("disabled", true);
});
it("creates a conversation using project id and navigates to chat", async () => {
  const c = {
    localId: "c",
    serverProfileId: "s",
    projectId: "p2",
    sessionId: null,
    title: "New",
    createdAt: 1,
    lastAccessedAt: 1,
  };
  const data: Snapshot = {
    ...empty,
    unlocked: true,
    servers: [
      {
        id: "s",
        name: "Home",
        baseUrl: "https://rei.example",
        hasCredential: true,
      },
    ],
    selectedServer: "s",
  };
  const spy = vi.fn(async (name: string) => {
    if (name === "app_snapshot") return data;
    if (name === "projects_list")
      return [
        { id: "p1", name: "rei", path: "/a" },
        { id: "p2", name: "rei", path: "/b" },
      ];
    if (name === "conversation_create") {
      data.conversations = [c];
      return c;
    }
    return undefined;
  });
  render(<App call={spy as Command} subscriptions={events} />);
  fireEvent.click(await screen.findByRole("button", { name: "新しい会話" }));
  const project = await screen.findByLabelText(/rei.*\/b/);
  fireEvent.click(project);
  fireEvent.click(screen.getByRole("button", { name: "会話を開始" }));
  await waitFor(() =>
    expect(spy).toHaveBeenCalledWith("conversation_create", {
      serverId: "s",
      projectId: "p2",
      title: "",
    }),
  );
  expect(await screen.findByPlaceholderText("れいに依頼する…")).toBeTruthy();
});
it("shows a safe error when the native boundary is unavailable", async () => {
  render(
    <App
      call={
        vi.fn(async () => {
          throw new Error("secret");
        }) as Command
      }
      subscriptions={events}
    />,
  );
  expect(await screen.findByRole("alert")).toBeTruthy();
  expect(screen.queryByText("secret")).toBeNull();
});
