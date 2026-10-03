import { test, expect } from "@playwright/test";
test("Workspace read/write/background forms use the shared UI on desktop and mobile", async ({
  page,
}) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: "Workspace", exact: true }).click();
  await page.getByRole("button", { name: "取得", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Fixture feed" }),
  ).toBeVisible();
  await page
    .getByRole("combobox", { name: "操作", exact: true })
    .selectOption("createFeed");
  await page.getByLabel("URL", { exact: true }).fill("https://example.com/rss");
  await page.getByLabel("表示名", { exact: true }).fill("Created feed");
  await page.getByRole("button", { name: "作成", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Created feed" }),
  ).toBeVisible();
  await page
    .getByRole("combobox", { name: "操作", exact: true })
    .selectOption("summary");
  await page.getByLabel("URL", { exact: true }).fill("https://example.com");
  await page.getByRole("button", { name: "開始", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Summary: https://example.com" }),
  ).toBeVisible();
  await expect(
    page.getByText("Fixture summary result", { exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
