import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { ActivityObservationContext } from "./ActivityObservationContext";
afterEach(cleanup);
it("keeps lock and a failed read quiet without retry or changing work context", async () => {
  const call = vi.fn().mockRejectedValue(new Error("offline"));
  const view = render(
    <ActivityObservationContext
      call={call as Command}
      serverId="s"
      projects={projects}
    />,
  );
  await userEvent.selectOptions(screen.getByLabelText("文脈のProject"), "p");
  await userEvent.click(
    screen.getByRole("button", { name: "保存観測時の文脈を取得" }),
  );
  await screen.findByRole("alert");
  expect(call).toHaveBeenCalledTimes(1);
  view.rerender(
    <ActivityObservationContext
      call={call as Command}
      serverId={null}
      projects={projects}
    />,
  );
  expect(
    (
      screen.getByRole("button", {
        name: "保存観測時の文脈を取得",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  expect(call).toHaveBeenCalledTimes(1);
});
it("does not display a response from an unknown scope or another project", async () => {
  const value = structuredClone(result);
  value.items[0].fields.find(([key]) => key === "Project")![1] = "q";
  const call = vi.fn().mockResolvedValue(value);
  render(
    <ActivityObservationContext
      call={call as Command}
      serverId="s"
      projects={projects}
    />,
  );
  await userEvent.selectOptions(screen.getByLabelText("文脈のProject"), "p");
  await userEvent.click(
    screen.getByRole("button", { name: "保存観測時の文脈を取得" }),
  );
  await screen.findByRole("alert");
  expect(screen.queryByText("<script>保存された出典</script>")).toBeNull();
});
it("discards a previous date response even when the same project remains selected", async () => {
  let finish!: (value: unknown) => void;
  const call = vi.fn().mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  render(
    <ActivityObservationContext
      call={call as Command}
      serverId="s"
      projects={projects}
    />,
  );
  await userEvent.selectOptions(screen.getByLabelText("文脈のProject"), "p");
  await userEvent.click(
    screen.getByRole("button", { name: "保存観測時の文脈を取得" }),
  );
  await userEvent.type(screen.getByLabelText("文脈の対象日"), "2026-10-04");
  finish(result);
  await waitFor(() =>
    expect(screen.queryByText("<script>保存された出典</script>")).toBeNull(),
  );
  expect(call).toHaveBeenCalledTimes(1);
});
const projects = [
  { id: "p", name: "rei", path: "private" },
  { id: "q", name: "other", path: "private2" },
];
const result = {
  title: "Context",
  items: [
    {
      id: null,
      title: "保存観測",
      fields: [
        ["Scope", "PROJECT_OBSERVATION_CONTEXT"],
        ["Project", "p"],
        ["Date", "2026-10-05"],
        ["Zone", "Z"],
        ["Partial", "true"],
        ["MissingContextRecords", "2"],
        ["LinkedObservations", "1"],
        ["Report", "<script>保存された出典</script>"],
      ],
    },
  ],
};
it("reads only after project selection and explicit request and renders report as text", async () => {
  const call = vi.fn().mockResolvedValue(result);
  render(
    <ActivityObservationContext
      call={call as Command}
      serverId="s"
      projects={projects}
    />,
  );
  expect(call).not.toHaveBeenCalled();
  await userEvent.selectOptions(screen.getByLabelText("文脈のProject"), "p");
  await userEvent.type(screen.getByLabelText("文脈の対象日"), "2026-10-05");
  await userEvent.click(
    screen.getByRole("button", { name: "保存観測時の文脈を取得" }),
  );
  await screen.findByText("<script>保存された出典</script>");
  expect(document.querySelector("script")).toBeNull();
  expect(call).toHaveBeenCalledExactlyOnceWith("workspace_execute", {
    serverId: "s",
    operation: {
      operation: "activityObservationContext",
      projectId: "p",
      date: "2026-10-05",
    },
  });
  expect(screen.getByText(/出典欠落.*2/)).toBeTruthy();
});
it("does not display a late response after project selection changes", async () => {
  let finish!: (value: unknown) => void;
  const call = vi.fn().mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  render(
    <ActivityObservationContext
      call={call as Command}
      serverId="s"
      projects={projects}
    />,
  );
  await userEvent.selectOptions(screen.getByLabelText("文脈のProject"), "p");
  await userEvent.click(
    screen.getByRole("button", { name: "保存観測時の文脈を取得" }),
  );
  await userEvent.selectOptions(screen.getByLabelText("文脈のProject"), "q");
  finish(result);
  await waitFor(() =>
    expect(screen.queryByText("<script>保存された出典</script>")).toBeNull(),
  );
  expect(call).toHaveBeenCalledTimes(1);
});
