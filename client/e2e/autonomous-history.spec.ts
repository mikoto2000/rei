import { test, expect } from "@playwright/test";
import { openNavigation } from "./navigation";

test("reads publication and generation history and reopens the latest reviewed body", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await openNavigation(page, "投稿案");
  await page.getByRole("button", { name: "投稿案を確認 draft-one" }).click();
  await page.getByRole("button", { name: "本文を編集" }).click();
  await page
    .getByLabel("投稿本文", { exact: true })
    .fill("履歴から戻る最新の本文です。");
  await page.getByRole("button", { name: "編集を保存" }).click();
  await page.getByRole("button", { name: "保留して閉じる" }).click();
  await page.getByRole("button", { name: "投稿・生成履歴を表示" }).click();
  const history = page.getByRole("region", { name: "投稿・生成履歴" });
  await expect(history.getByText(/こちらは履歴の本文です/)).toBeVisible();
  await expect(
    history.getByText(
      "公開済み・公開中・結果不明の投稿と内容が重複しています。",
    ),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/${info.project.name}-autonomous-history.png`,
    fullPage: true,
  });
  await history.getByRole("button", { name: "投稿案を開く draft-one" }).click();
  const detail = page.getByRole("region", { name: "投稿案の詳細" });
  await expect(detail.getByLabel("投稿本文の全文")).toHaveText(
    "履歴から戻る最新の本文です。",
  );
  await expect(
    detail.getByText("PENDING_REVIEW", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("dialog", { name: "投稿の公開確認" }),
  ).toHaveCount(0);
});

test("registers only redacted feedback and requires an explicit permission for its saved version", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await openNavigation(page, "投稿案");
  await page.getByRole("button", { name: "投稿案を確認 draft-one" }).click();
  await page
    .getByLabel("見送り理由（非公開・任意）")
    .fill("PRIVATE_PERSONAL_DETAIL / もっと簡潔に");
  await page.getByRole("button", { name: "見送る", exact: true }).click();
  await page
    .getByRole("button", { name: "見送り理由を資料の入力欄へコピー" })
    .click();
  const input = page.getByLabel("新しい話題の本文");
  await expect(input).toHaveValue("PRIVATE_PERSONAL_DETAIL / もっと簡潔に");
  await expect(
    page.getByRole("checkbox", { name: "投稿案の生成に利用してよい" }),
  ).not.toBeChecked();
  await input.fill("PUBLIC_FEEDBACK もっと簡潔に");
  await page.getByRole("button", { name: "話題を登録" }).click();
  const source = page.getByRole("region", { name: "話題の詳細" });
  await expect(source.getByLabel("話題の本文")).toHaveValue(
    "PUBLIC_FEEDBACK もっと簡潔に",
  );
  await expect(
    source.getByRole("button", { name: "生成への利用を許可" }),
  ).toBeVisible();
  await source.getByRole("button", { name: "生成への利用を許可" }).click();
  await expect(
    source.getByRole("button", { name: "生成への利用を取り消す" }),
  ).toBeVisible();
  await expect(
    page
      .getByRole("region", { name: "投稿案の詳細" })
      .getByText("PRIVATE_PERSONAL_DETAIL / もっと簡潔に", { exact: true }),
  ).toBeVisible();
  await expect(source).not.toContainText("PRIVATE_PERSONAL_DETAIL");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/${info.project.name}-autonomous-feedback.png`,
    fullPage: true,
  });
});
