import { test, expect } from "@playwright/test";
import { openNavigation } from "./navigation";

test("global draft notices open current full review without a Project", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html?no-projects");
  await openNavigation(page, "Inbox・承認");
  const notices = page.getByRole("region", { name: "投稿案の通知" });
  await expect(
    notices.getByRole("button", { name: "投稿案の詳細を開く" }),
  ).toBeVisible();
  await page.screenshot({
    path: `test-results/${info.project.name}-autonomous-notice.png`,
    fullPage: true,
  });
  await notices.getByRole("button", { name: "投稿案の詳細を開く" }).click();
  const detail = page.getByRole("region", { name: "投稿案の詳細" });
  await expect(detail).toContainText("本文の全文を確認してください。");
  await expect(detail).toContainText("rei.example");
  await expect(detail).toContainText("PENDING_REVIEW");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "見送る", exact: true }).click();
  await openNavigation(page, "Inbox・承認");
  await expect(
    notices.getByRole("button", { name: "投稿案の詳細を開く" }),
  ).toHaveCount(0);
});

test("reading a notice leaves its draft pending and available for later review", async ({
  page,
}) => {
  await page.goto("/e2e/fixture.html?no-projects");
  await openNavigation(page, "Inbox・承認");
  await page.getByRole("button", { name: "投稿案の通知を既読にする" }).click();
  await expect(
    page.getByRole("button", { name: "投稿案の詳細を開く" }),
  ).toHaveCount(0);
  await openNavigation(page, "投稿案");
  await page.getByRole("button", { name: "投稿案を確認 draft-one" }).click();
  await expect(
    page.getByRole("region", { name: "投稿案の詳細" }),
  ).toContainText("PENDING_REVIEW");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
