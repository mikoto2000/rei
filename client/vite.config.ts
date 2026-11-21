import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: { port: 1420, strictPort: true },
  experimental: { bundledDev: mode !== "test" },
  input: mode === "e2e" ? "e2e/fixture.html" : undefined,
  test: {
    environment: "jsdom",
    restoreMocks: true,
    include: ["src/**/*.test.{ts,tsx}"],
  },
}));
