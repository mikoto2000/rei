import { test, expect } from "@playwright/test";
test("GitHub facts appear in Inbox and Task results without granting execution", async ({
  page,
}) => {
  await page.goto("/e2e/fixture.html?github=1");
  await page.getByRole("button", { name: "Inbox・承認", exact: true }).click();
  await expect(
    page.getByText("GITHUB_REVIEW_SUBMITTED", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("GitHub review received; inspect the saved fact"),
  ).toBeVisible();
  await expect(page.getByText(/Run: なし/)).toBeVisible();
  await page.getByRole("button", { name: "通知を確認済みにする" }).click();
  await expect(page.getByText("未確認の通知はありません。")).toBeVisible();
  await page.getByRole("button", { name: "Task Manager", exact: true }).click();
  await expect(page.getByText("QUEUED", { exact: true })).toBeVisible();
  await expect(
    page.getByText("GITHUB_EVENT: github-fact", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "再開", exact: true }),
  ).toHaveCount(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
