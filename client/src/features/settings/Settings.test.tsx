import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { Settings } from "./Settings";
afterEach(cleanup);
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
