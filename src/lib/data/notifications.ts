import "server-only";
import { createClient } from "@/lib/supabase/server";
import type { Profile, NotificationType } from "@/lib/database.types";

export type NotificationActor = Pick<Profile, "id" | "username" | "full_name" | "avatar_path">;

export type NotificationItem = {
  id: string;
  type: NotificationType;
  read_at: string | null;
  created_at: string;
  post_id: string | null;
  story_id: string | null;
  conversation_id: string | null; // a mention in a group chat
  actor: NotificationActor;
  // Thumbnail for the right-hand side (null if the post/story is gone)
  thumb: string | null;
  thumbBucket: "posts" | "stories";
  commentBody: string | null;
  // Mentions in a group chat: the message and the group's name
  messageBody: string | null;
  groupTitle: string | null;
};

export async function getNotifications(userId: string, limit = 100): Promise<NotificationItem[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("notifications")
    .select(
      "id, type, read_at, created_at, post_id, story_id, conversation_id, actor:profiles!notifications_actor_id_fkey(id, username, full_name, avatar_path), post:posts(images:post_images(path, thumb_path, position)), comment:comments(body), story:stories(image_path), message:messages(body), conversation:conversations(title)",
    )
    .eq("user_id", userId)
    .order("created_at", { ascending: false })
    .limit(limit);

  const out: NotificationItem[] = [];
  for (const n of data ?? []) {
    if (!n.actor) continue;
    const first = n.post ? [...n.post.images].sort((a, b) => a.position - b.position)[0] : undefined;
    const cover = first ? (first.thumb_path ?? first.path) : null; // small tile: thumbnail when there is one
    out.push({
      id: n.id,
      type: n.type,
      read_at: n.read_at,
      created_at: n.created_at,
      post_id: n.post_id,
      story_id: n.story_id,
      conversation_id: n.conversation_id,
      actor: n.actor,
      thumb: n.type === "story_reaction" ? (n.story?.image_path ?? null) : cover,
      thumbBucket: n.type === "story_reaction" ? "stories" : "posts",
      commentBody: n.comment?.body ?? null,
      messageBody: n.message?.body ?? null,
      groupTitle: n.conversation?.title ?? null,
    });
  }
  return out;
}

export async function getUnreadNotificationCount(userId: string): Promise<number> {
  const supabase = await createClient();
  const { count } = await supabase
    .from("notifications")
    .select("id", { count: "exact", head: true })
    .eq("user_id", userId)
    .is("read_at", null);
  return count ?? 0;
}
