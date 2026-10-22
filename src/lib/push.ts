import "server-only";
import webpush from "web-push";
import { createAdminClient } from "@/lib/supabase/admin";
import { sendFcm } from "@/lib/fcm";

// icon: the person behind it (their avatar URL); the Android app shows it, browsers use the Dheu icon.
export type PushPayload = { title: string; body: string; url: string; tag?: string; icon?: string };

let configured = false;
function configure() {
  if (configured) return true;
  const pub = process.env.NEXT_PUBLIC_VAPID_PUBLIC_KEY;
  const priv = process.env.VAPID_PRIVATE_KEY;
  if (!pub || !priv) return false;
  // Subject is a contact for the push services and must be https:// or mailto:.
  // Only trust NEXT_PUBLIC_SITE_URL when it is a real https address.
  const site = process.env.NEXT_PUBLIC_SITE_URL ?? "";
  const subject = /^https:\/\//.test(site) ? site : "https://dheu-beige.vercel.app";
  webpush.setVapidDetails(subject, pub, priv);
  configured = true;
  return true;
}

// Send a push to every device the given members have enabled: browsers and
// installed PWAs (Web Push) and the Android app (Firebase). Dead
// subscriptions and tokens (unsubscribed / uninstalled) are removed as we go.
export async function sendPush(userIds: string[], payload: PushPayload): Promise<{ sent: number; removed: number }> {
  if (userIds.length === 0) return { sent: 0, removed: 0 };
  const [web, app] = await Promise.all([sendWebPush(userIds, payload), sendAppPush(userIds, payload)]);
  return { sent: web.sent + app.sent, removed: web.removed + app.removed };
}

async function sendAppPush(userIds: string[], payload: PushPayload): Promise<{ sent: number; removed: number }> {
  const admin = createAdminClient();
  const { data: devices } = await admin.from("push_devices").select("token").in("user_id", userIds);
  if (!devices || devices.length === 0) return { sent: 0, removed: 0 };
  const { sent, dead } = await sendFcm(
    devices.map((d) => d.token),
    payload,
  );
  if (dead.length) await admin.from("push_devices").delete().in("token", dead);
  return { sent, removed: dead.length };
}

async function sendWebPush(userIds: string[], payload: PushPayload): Promise<{ sent: number; removed: number }> {
  if (!configure()) return { sent: 0, removed: 0 };
  const admin = createAdminClient();
  const { data: subs } = await admin
    .from("push_subscriptions")
    .select("id, endpoint, p256dh, auth")
    .in("user_id", userIds);
  if (!subs || subs.length === 0) return { sent: 0, removed: 0 };

  let sent = 0;
  const dead: string[] = [];
  await Promise.all(
    subs.map(async (s) => {
      try {
        await webpush.sendNotification(
          { endpoint: s.endpoint, keys: { p256dh: s.p256dh, auth: s.auth } },
          JSON.stringify(payload),
          { TTL: 60 * 60 * 24, urgency: "normal" },
        );
        sent++;
      } catch (err) {
        const status = (err as { statusCode?: number }).statusCode;
        if (status === 404 || status === 410) dead.push(s.id);
        else console.warn("[push] failed", status, (err as Error).message?.slice(0, 120));
      }
    }),
  );
  if (dead.length) await admin.from("push_subscriptions").delete().in("id", dead);
  return { sent, removed: dead.length };
}
