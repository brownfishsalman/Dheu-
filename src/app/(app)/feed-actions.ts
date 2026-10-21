"use server";

import { getUserId } from "@/lib/data/session";
import { getFeedPage, type FeedPost } from "@/lib/data/posts";

export async function loadMoreFeed(
  cursor: string,
): Promise<{ posts: FeedPost[]; nextCursor: string | null }> {
  const userId = await getUserId();
  if (!userId) return { posts: [], nextCursor: null };
  return getFeedPage(cursor);
}
