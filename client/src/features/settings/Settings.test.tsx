import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { Settings } from "./Settings";
afterEach(cleanup);
it("shows the specific connection error while retaining successful reachability", () => {
  render(
    <Settings
      servers={[
        {
          id: "s",
          name: "Home",
          baseUrl: "http://rei.example",
          hasCredential: true,
        },
      ]}
      connections={{
        s: {
          serverId: "s",
          state: "AUTH_FAILED",
          reachable: true,
          authenticated: false,
          error: "AuthenticationFailed",
        },
      }}
      unlocked
      notifications={false}
      pending={false}
      onUnlock={vi.fn()}
      onSave={vi.fn()}
      onRemove={vi.fn()}
      onDeleteKey={vi.fn()}
      onTest={vi.fn()}
      onNotifications={vi.fn()}
    />,
  );
  expect(screen.getByText(/Server reachable:/).textContent).toContain("Yes");
  expect(screen.getByRole("alert").textContent).toContain("API Key を確認");
});

it("does not label an in-progress connection as unreachable", () => {
  render(
    <Settings
      servers={[
        {
          id: "s",
          name: "Home",
          baseUrl: "http://rei.example",
          hasCredential: false,
        },
      ]}
      connections={{
        s: {
          serverId: "s",
          state: "CONNECTING",
          reachable: false,
          authenticated: false,
          error: null,
        },
      }}
      unlocked
      notifications={false}
      pending={false}
      onUnlock={vi.fn()}
      onSave={vi.fn()}
      onRemove={vi.fn()}
      onDeleteKey={vi.fn()}
      onTest={vi.fn()}
      onNotifications={vi.fn()}
    />,
  );
  expect(screen.getByText(/Server reachable:/).textContent).toContain("確認中");
  expect(screen.queryByRole("alert")).toBeNull();
});
it("clears credential input immediately and does not retain it in UI state", () => {
  const save = vi.fn();
  render(
    <Settings
      servers={[]}
      connections={{}}
      unlocked
      notifications={false}
      pending={false}
      onUnlock={vi.fn()}
      onSave={save}
      onRemove={vi.fn()}
      onDeleteKey={vi.fn()}
      onTest={vi.fn()}
      onNotifications={vi.fn()}
    />,
  );
  fireEvent.change(screen.getByLabelText("Name"), {
    target: { value: "Home" },
  });
  fireEvent.change(screen.getByLabelText("URL"), {
    target: { value: "https://rei.example" },
  });
  fireEvent.change(screen.getByLabelText("API Key"), {
    target: { value: "not-for-output" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  expect(save).toHaveBeenCalledWith(
    null,
    "Home",
    "https://rei.example",
    "not-for-output",
  );
  expect(screen.getByLabelText("API Key")).toHaveProperty("value", "");
});
