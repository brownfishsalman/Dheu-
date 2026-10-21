import "server-only";
import { createClient } from "@/lib/supabase/server";
import type { Profile } from "@/lib/database.types";

export type NoteAuthor = Pick<Profile, "id" | "username" | "full_name" | "avatar_path">;

export type NoteItem = {
  author: NoteAuthor;
  body: string;
  created_at: string;
  expires_at: string;
};

// Live notes I can see: my own and my mutual follows' (RLS decides), newest first.
export async function getNotes(): Promise<NoteItem[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("notes")
    .select("body, created_at, expires_at, author:profiles!notes_author_id_fkey(id, username, full_name, avatar_path)")
    .order("created_at", { ascending: false })
    .limit(60);
  return (data ?? []).filter((n): n is typeof n & { author: NoteAuthor } => n.author !== null);
}
