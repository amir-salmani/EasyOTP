/**
 * Telegram Bot API calls.
 *
 * The device cannot reach api.telegram.org from Iran, so the relay makes every
 * call on its behalf (DECISIONS D4). Bot tokens arrive inside a sealed envelope,
 * are used within the request, and are never persisted or logged.
 */
export interface TgResult<T> {
  ok: boolean;
  result?: T;
  description?: string;
  error_code?: number;
}

const API = "https://api.telegram.org";

async function call<T>(token: string, method: string, body: unknown): Promise<TgResult<T>> {
  const res = await fetch(`${API}/bot${token}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  // Telegram reports application errors in the body with HTTP 200 or 4xx alike.
  return (await res.json()) as TgResult<T>;
}

export const getMe = (token: string) =>
  call<{ id: number; username: string; first_name: string }>(token, "getMe", {});

export const setWebhook = (token: string, url: string, secretToken: string) =>
  call<boolean>(token, "setWebhook", {
    url,
    secret_token: secretToken,
    // Only what the product uses. Narrower updates mean less of the user's
    // Telegram activity transits the relay at all.
    allowed_updates: ["message", "callback_query"],
    drop_pending_updates: true,
  });

export const sendMessage = (token: string, chatId: number | string, text: string) =>
  call<{ message_id: number }>(token, "sendMessage", {
    chat_id: chatId,
    text,
    // OTP codes are wrapped in <code> by the device; Telegram clients make those
    // tap-to-copy, which is the whole point of the product.
    parse_mode: "HTML",
    disable_web_page_preview: true,
  });
