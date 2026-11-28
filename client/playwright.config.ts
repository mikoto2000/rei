import { defineConfig } from "@playwright/test";
const port = Number(process.env.REI_CLIENT_E2E_PORT ?? "1420");
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
  throw new Error(
    "REI_CLIENT_E2E_PORT must be an integer between 1024 and 65535",
  );
}
const baseURL = `http://127.0.0.1:${port}`;
export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/global-setup.ts",
  workers: 2,
  use: { baseURL, channel: "chrome", headless: true },
  webServer: {
    command: `npm run dev -- --mode e2e --port ${port}`,
    url: `${baseURL}/e2e/fixture.html`,
    reuseExistingServer: !process.env.CI,
  },
  projects: [
    { name: "desktop", use: { viewport: { width: 1280, height: 900 } } },
    {
      name: "mobile",
      use: {
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
});
