import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          // Test-only HPKE keypair, generated for this suite and valid nowhere.
          // Public half lives in test/keys.ts so tests can seal envelopes.
          RELAY_PRIVATE_KEY: "0NE6zAJS0Y6fJcEZLQgc8IB3xfrXmoxpNzK0WezOQ04",
          WEBHOOK_BASE: "https://relay.test",
        },
      },
    }),
  ],
});
