import "server-only";
import { createClient } from "@/lib/supabase/server";

// Unread chat (incl. announcements) + activity counts for the app shell, in one request.
export async function getBadgeCounts(): Promise<{ messages: number; activity: number }> {
  const supabase = await createClient();
  const { data } = await supabase.rpc("badge_counts").single();
  return { messages: data?.messages ?? 0, activity: data?.activity ?? 0 };
}
