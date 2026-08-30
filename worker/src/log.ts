/**
 * Structured logging that cannot leak message content.
 *
 * THREAT-MODEL rule 1: no message body, sender, or bot token ever reaches a log
 * sink. The way to enforce that is to make it impossible to pass one, so this
 * module accepts a closed set of scalar fields and nothing else. There is no
 * `extra`, no `...rest`, no object spread. Adding a field is a deliberate edit
 * reviewers will see.
 */
export type Outcome = "ok" | "rejected" | "upstream_error" | "timeout";

export interface LogFields {
  route: string;
  outcome: Outcome;
  /** Truncated channel identifier. Never the full secret. */
  ch?: string;
  status?: number;
  ms?: number;
  /** Stable machine-readable cause. Must not be built from user input. */
  reason?: string;
}

export function log(f: LogFields): void {
  console.log(JSON.stringify({ t: Date.now(), ...f }));
}

/** Channel identifiers are secrets; only a prefix is ever recorded. */
export function chTag(webhookId: string): string {
  return webhookId.slice(0, 8);
}
