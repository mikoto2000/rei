import { render, screen, waitFor } from "@testing-library/react";
import { it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { WorkContextNotice } from "./WorkContextNotice";
it("shows bounded handoff once per conversation and honors the server setting", async () => {
  const call = vi.fn().mockResolvedValue({
    title: "Work Context",
    items: [
      {
        id: "p",
        title: "historical",
        fields: [
          ["引き継ぎ", "Native verification pending"],
          ["Auto present", "true"],
        ],
      },
    ],
  });
  const seen = new Set<string>();
  const props = {
    call: call as Command,
    serverId: "s",
    projectId: "p",
    conversationId: "c1",
    seen,
  };
  const view = render(<WorkContextNotice {...props} />);
  expect(await screen.findByText("Native verification pending")).toBeTruthy();
  view.unmount();
  render(<WorkContextNotice {...props} />);
  expect(call).toHaveBeenCalledTimes(1);
  call.mockResolvedValue({
    title: "Work Context",
    items: [
      {
        fields: [
          ["引き継ぎ", "hidden"],
          ["Auto present", "false"],
        ],
      },
    ],
  });
  render(<WorkContextNotice {...props} conversationId="c2" />);
  await waitFor(() => expect(call).toHaveBeenCalledTimes(2));
  expect(screen.queryByText("hidden")).toBeNull();
});
