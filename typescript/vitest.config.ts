import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    coverage: {
      // The SDK itself only — not built output or the examples/e2e app (which is exercised
      // against a live server in CI, not by the unit suite).
      include: ["src/**"],
    },
  },
});
