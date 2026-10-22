import "server-only";
import { createSign } from "node:crypto";
import type { PushPayload } from "@/lib/push";

// Firebase Cloud Messaging (HTTP v1) for the Android app, without the
// Firebase Admin SDK: a service-account JWT buys a one-hour access token,
// then one request per device.
//
// FCM_SERVICE_ACCOUNT holds the service account's JSON key (Firebase console
// → Project settings → Service accounts → Generate new private key), pasted
// as is or base64-encoded.

type ServiceAccount = { project_id: string; client_email: string; private_key: string };

let account: ServiceAccount | null | undefined;
export function fcmAccount(): ServiceAccount | null {
  if (account !== undefined) return account;
  const raw = process.env.FCM_SERVICE_ACCOUNT?.trim();
  account = null;
  if (!raw) return null;
  try {
    const json = raw.startsWith("{") ? raw : Buffer.from(raw, "base64").toString("utf8");
    const parsed = JSON.parse(json) as Partial<ServiceAccount>;
    if (parsed.project_id && parsed.client_email && parsed.private_key) account = parsed as ServiceAccount;
    else console.error("[fcm] FCM_SERVICE_ACCOUNT is missing project_id, client_email or private_key");
  } catch {
    console.error("[fcm] FCM_SERVICE_ACCOUNT is not valid JSON");
  }
  return account;
}

let cached: { token: string; expires: number } | null = null;

async function accessToken(sa: ServiceAccount): Promise<string> {
  if (cached && cached.expires > Date.now() + 60_000) return cached.token;
  const now = Math.floor(Date.now() / 1000);
  const encode = (v: object) => Buffer.from(JSON.stringify(v)).toString("base64url");
  const unsigned = `${encode({ alg: "RS256", typ: "JWT" })}.${encode({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })}`;
  const signature = createSign("RSA-SHA256").update(unsigned).sign(sa.private_key).toString("base64url");

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${signature}`,
    }),
  });
  if (!res.ok) throw new Error(`FCM sign-in failed (${res.status}): ${(await res.text()).slice(0, 200)}`);
  const body = (await res.json()) as { access_token: string; expires_in: number };
  cached = { token: body.access_token, expires: Date.now() + body.expires_in * 1000 };
  return body.access_token;
}

// Send to each device token. Returns how many went out and which tokens are
// dead (the app was uninstalled or the token rotated), for the caller to delete.
export async function sendFcm(tokens: string[], payload: PushPayload): Promise<{ sent: number; dead: string[] }> {
  const sa = fcmAccount();
  if (!sa || tokens.length === 0) return { sent: 0, dead: [] };
  const token = await accessToken(sa);
  const url = `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`;

  // Data-only, so the app builds the notification itself: its channel, the
  // sender's photo, and the screen a tap opens. High priority wakes the phone.
  const data: Record<string, string> = { title: payload.title, body: payload.body, url: payload.url };
  if (payload.tag) data.tag = payload.tag;
  if (payload.icon) data.icon = payload.icon;

  let sent = 0;
  const dead: string[] = [];
  await Promise.all(
    tokens.map(async (t) => {
      const res = await fetch(url, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({ message: { token: t, data, android: { priority: "high", ttl: "86400s" } } }),
      });
      if (res.ok) return void sent++;
      const text = await res.text();
      // 404 UNREGISTERED: the app is gone. 400 on the token itself: never valid.
      if (res.status === 404 || (res.status === 400 && /registration token/i.test(text))) dead.push(t);
      else console.warn("[fcm] failed", res.status, text.slice(0, 160));
    }),
  );
  return { sent, dead };
}
