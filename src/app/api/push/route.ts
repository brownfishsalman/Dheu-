import { NextResponse } from "next/server";
import { createAdminClient } from "@/lib/supabase/admin";
import { sendPush, type PushPayload } from "@/lib/push";

// Called by the database (pg_net trigger) whenever a notification, message or
// announcement row is inserted. Authenticated with a shared secret held in
// Supabase Vault and in the PUSH_WEBHOOK_SECRET environment variable.

type NotificationRow = {
  id: string;
  user_id: string;
  actor_id: string;
  type: "like" | "follow" | "comment" | "story_reaction" | "follow_request" | "follow_accepted";
  post_id: string | null;
  comment_id: string | null;
};
type MessageRow = {
  id: string;
  conversation_id: string;
  sender_id: string;
  body: string;
  image_path: string | null;
  audio_path: string | null;
  kind: "text" | "system";
  event: { type: string; actor: string; targets?: string[]; title?: string } | null;
};
type AnnouncementRow = { id: string; body: string };

async function usernameOf(id: string) {
  const admin = createAdminClient();
  const { data } = await admin.from("profiles").select("username").eq("id", id).maybeSingle();
  return data?.username ?? "Someone";
}

export async function POST(request: Request) {
  const secret = process.env.PUSH_WEBHOOK_SECRET;
  if (!secret || request.headers.get("x-push-secret") !== secret) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  }

  const { table, record } = (await request.json()) as { table: string; record: unknown };
  const admin = createAdminClient();

  let recipients: string[] = [];
  let payload: PushPayload | null = null;

  if (table === "notifications") {
    const n = record as NotificationRow;
    const actor = await usernameOf(n.actor_id);
    recipients = [n.user_id];
    const map: Record<NotificationRow["type"], { body: string; url: string }> = {
      like: { body: `${actor} liked your post`, url: n.post_id ? `/p/${n.post_id}` : "/activity" },
      comment: { body: `${actor} commented on your post`, url: n.post_id ? `/p/${n.post_id}` : "/activity" },
      follow: { body: `${actor} started following you`, url: `/u/${actor}` },
      follow_request: { body: `${actor} wants to follow you`, url: "/activity" },
      follow_accepted: { body: `${actor} accepted your follow request`, url: `/u/${actor}` },
      story_reaction: { body: `${actor} reacted to your story`, url: `/stories/${n.user_id}` },
    };
    const m = map[n.type];
    if (!m) return NextResponse.json({ ok: true, skipped: "unknown type" });
    if (n.type === "comment" && n.comment_id) {
      const { data: c } = await admin.from("comments").select("body").eq("id", n.comment_id).maybeSingle();
      if (c?.body) m.body = `${actor} commented: ${c.body.slice(0, 80)}`;
    }
    payload = { title: "Dheu", body: m.body, url: m.url, tag: `notif-${n.type}-${n.actor_id}` };
  } else if (table === "messages") {
    const msg = record as MessageRow;
    const [{ data: conv }, { data: blocks }] = await Promise.all([
      admin
        .from("conversations")
        .select("is_group, title, members:conversation_members(user_id)")
        .eq("id", msg.conversation_id)
        .maybeSingle(),
      // In a group, people on either side of a block with the sender can't see the message — no push either.
      admin.from("blocks").select("blocker_id, blocked_id").or(`blocker_id.eq.${msg.sender_id},blocked_id.eq.${msg.sender_id}`),
    ]);
    if (!conv) return NextResponse.json({ ok: true, skipped: "no conversation" });
    const blocked = new Set((blocks ?? []).flatMap((b) => [b.blocker_id, b.blocked_id]));
    const sender = await usernameOf(msg.sender_id);
    const url = `/messages/${msg.conversation_id}`;
    const tag = `chat-${msg.conversation_id}`;

    if (msg.kind === "system") {
      // System lines stay quiet, except that people who were just added should hear about it.
      if (msg.event?.type !== "added") return NextResponse.json({ ok: true, skipped: "system line" });
      recipients = (msg.event.targets ?? []).filter((id) => !blocked.has(id));
      payload = { title: conv.title ?? "Group", body: `${sender} added you to the group`, url, tag };
    } else {
      recipients = conv.members.map((m) => m.user_id).filter((id) => id !== msg.sender_id && !(conv.is_group && blocked.has(id)));
      const text = msg.body ? msg.body.slice(0, 100) : msg.audio_path ? "🎤 Voice message" : "📷 Photo";
      payload = conv.is_group
        ? { title: conv.title ?? "Group", body: `${sender}: ${text}`, url, tag }
        : { title: sender, body: text, url, tag };
    }
  } else if (table === "announcements") {
    const a = record as AnnouncementRow;
    const { data: subs } = await admin.from("push_subscriptions").select("user_id");
    recipients = [...new Set((subs ?? []).map((s) => s.user_id))];
    payload = { title: "Dheu announcements", body: a.body.slice(0, 140), url: "/messages/announcements", tag: "announcement" };
  } else {
    return NextResponse.json({ ok: true, skipped: "unknown table" });
  }

  try {
    const result = await sendPush(recipients, payload);
    return NextResponse.json({ ok: true, ...result });
  } catch (err) {
    // Surface the reason (this endpoint is secret-protected, so it's safe to be specific).
    console.error("[push] send failed:", err);
    return NextResponse.json({ ok: false, error: (err as Error).message }, { status: 500 });
  }
}
