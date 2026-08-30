/**
 * ChannelMailbox — the rendezvous between Telegram and a phone behind CGNAT.
 *
 * The phone cannot be reached inbound, so a Telegram update has to wait
 * somewhere for the phone's long-poll to collect it. This is that somewhere,
 * and it is deliberately the smallest thing that works.
 *
 * DECISIONS D3: **no storage API calls anywhere in this class.** Updates live in
 * isolate memory for the seconds between arrival and collection. If the object
 * is evicted, queued updates are lost — that is the intended trade, not a bug.
 * Commands are user-initiated and retryable; persistence would turn the relay
 * into something holding other people's Telegram traffic at rest, which is the
 * exact property the product exists to avoid.
 */
import { DurableObject } from "cloudflare:workers";

/** Bounds memory per channel. Commands are interactive; a deep backlog is stale by definition. */
const MAX_QUEUED = 32;

export class ChannelMailbox extends DurableObject {
  private queue: string[] = [];
  private waiters: Array<(v: string[]) => void> = [];

  /** Called on webhook delivery. Hands straight to a waiting poller if there is one. */
  async deliver(update: string): Promise<void> {
    const waiter = this.waiters.shift();
    if (waiter) {
      waiter([update]);
      return;
    }
    this.queue.push(update);
    if (this.queue.length > MAX_QUEUED) this.queue.shift();
  }

  /** Long-poll. Returns immediately if anything is queued, else waits up to `holdMs`. */
  async drain(holdMs: number): Promise<string[]> {
    if (this.queue.length) {
      const batch = this.queue;
      this.queue = [];
      return batch;
    }
    return new Promise<string[]>((resolve) => {
      const waiter = (v: string[]) => {
        clearTimeout(timer);
        resolve(v);
      };
      const timer = setTimeout(() => {
        // Drop this waiter so a later deliver() does not resolve a dead request.
        const i = this.waiters.indexOf(waiter);
        if (i >= 0) this.waiters.splice(i, 1);
        resolve([]);
      }, holdMs);
      this.waiters.push(waiter);
    });
  }
}
