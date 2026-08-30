/// <reference types="@cloudflare/vitest-pool-workers/types" />
import type { Env } from "../src/index";

declare module "cloudflare:test" {
  // Gives `env` in tests the same shape the Worker sees, so a binding added to
  // wrangler.jsonc without updating Env is a type error, not a runtime surprise.
  interface ProvidedEnv extends Env {}
}
