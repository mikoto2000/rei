import { test, expect } from "@playwright/test";
import { openNavigation } from "./navigation";

test("reviews, edits, regenerates and holds a draft without publishing", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await openNavigation(page, "投稿案");
  await page.getByRole("button", { name: "投稿案を確認 draft-one" }).click();
  const detail = page.getByRole("region", { name: "投稿案の詳細" });
  await expect(detail.getByText("rei.example", { exact: true })).toBeVisible();
  await expect(
    detail.getByText("topic:source-one:1", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "本文を編集" }).click();
  await page
    .getByLabel("投稿本文", { exact: true })
    .fill("編集した本文\nhttps://example.com/" + "a".repeat(150));
  await page.getByRole("button", { name: "編集を保存" }).click();
  await expect(page.getByLabel("投稿本文の全文")).toContainText("編集した本文");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: "再生成", exact: true }).click();
  await expect(page.getByLabel("投稿本文の全文")).toContainText(
    "再生成された本文の全文です。",
  );
  await page.getByLabel("投稿本文の全文").scrollIntoViewIfNeeded();
  await page.screenshot({
    path: `test-results/${info.project.name}-autonomous-review.png`,
    fullPage: true,
  });
  await expect(
    page.getByRole("button", { name: /承認して投稿|投稿する/ }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "保留して閉じる" }).click();
  await expect(detail).toHaveCount(0);
  await page.getByRole("button", { name: "投稿案を確認 draft-one" }).click();
  await expect(page.getByLabel("投稿本文の全文")).toContainText(
    "再生成された本文の全文です。",
  );
  await page.getByLabel("見送り理由（非公開・任意）").fill("見送り理由");
  await page.getByRole("button", { name: "見送る", exact: true }).click();
  await expect(detail.getByText("REJECTED", { exact: true })).toBeVisible();
});
