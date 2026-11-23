import type { Page } from "@playwright/test";
export async function openNavigation(page: Page, name: string) {
  const destination = page.getByRole("button", { name, exact: true });
  if (!(await destination.isVisible()))
    await page.getByRole("button", { name: "その他", exact: true }).click();
  await destination.click();
}
