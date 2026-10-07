import { test, expect } from "@playwright/test";
test("Task Manager restores the server list and targets explicit resume without overflowing", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: "Task Manager", exact: true }).click();
  await expect(page.getByText("SUSPENDED", { exact: true })).toBeVisible();
  const resume = page.getByRole("button", { name: "再開", exact: true });
  await resume.scrollIntoViewIfNeeded();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/${info.project.name}-tasks.png`,
  });
  await resume.click();
  await expect(page.getByText("QUEUED", { exact: true })).toBeVisible();
});
