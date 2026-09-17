"use server";

import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { createClient } from "@/lib/supabase/server";
import { getMessages, type ChatMessage } from "@/lib/data/chat";
import { GROUP_MAX_MEMBERS } from "@/lib/constants";

export async function loadOlderMessages(conversationId: string, before: string): Promise<ChatMessage[]> {
  // RLS guarantees the caller can only read conversations they belong to.
  return getMessages(conversationId, before);
}

// ---------------------------------------------------------------------
// Groups. Every rule lives in the database functions; this layer only
// validates shapes and turns their error codes into sentences.
// ---------------------------------------------------------------------

export type GroupResult = { ok: true } | { ok: false; error: string };

const titleSchema = z.string().trim().min(1, "Give the group a name").max(60, "Name is too long (60 characters max)");
const idsSchema = z.array(z.uuid()).min(1, "Pick someone to add").max(GROUP_MAX_MEMBERS);

function friendly(message: string): string {
  if (message.includes("GROUP_TOO_SMALL")) return "Pick at least two other people.";
  if (message.includes("GROUP_TOO_BIG")) return `A group can have up to ${GROUP_MAX_MEMBERS} people.`;
  if (message.includes("GROUP_TITLE")) return "Give the group a name (up to 60 characters).";
  if (message.includes("NOT_OWNER")) return "Only the group's owner can remove people.";
  if (message.includes("not a member")) return "You're not in this group.";
  return "Something went wrong. Please try again.";
}

export async function createGroup(title: string, memberIds: string[]): Promise<{ ok: false; error: string }> {
  const t = titleSchema.safeParse(title);
  if (!t.success) return { ok: false, error: t.error.issues[0].message };
  const ids = idsSchema.safeParse(memberIds);
  if (!ids.success) return { ok: false, error: ids.error.issues[0].message };

  const supabase = await createClient();
  const { data: id, error } = await supabase.rpc("create_group_conversation", { p_title: t.data, p_member_ids: ids.data });
  if (error || !id) return { ok: false, error: friendly(error?.message ?? "") };
  redirect(`/messages/${id}`);
}

export async function addGroupMembers(conversationId: string, memberIds: string[]): Promise<{ ok: false; error: string }> {
  const ids = idsSchema.safeParse(memberIds);
  if (!ids.success) return { ok: false, error: ids.error.issues[0].message };

  const supabase = await createClient();
  const { error } = await supabase.rpc("add_group_members", { p_conversation_id: conversationId, p_member_ids: ids.data });
  if (error) return { ok: false, error: friendly(error.message) };
  revalidatePath(`/messages/${conversationId}`);
  redirect(`/messages/${conversationId}/info`);
}

export async function renameGroup(conversationId: string, title: string): Promise<GroupResult> {
  const t = titleSchema.safeParse(title);
  if (!t.success) return { ok: false, error: t.error.issues[0].message };
  const supabase = await createClient();
  const { error } = await supabase.rpc("rename_group", { p_conversation_id: conversationId, p_title: t.data });
  if (error) return { ok: false, error: friendly(error.message) };
  revalidatePath(`/messages/${conversationId}`);
  return { ok: true };
}

export async function removeGroupMember(conversationId: string, userId: string): Promise<GroupResult> {
  const supabase = await createClient();
  const { error } = await supabase.rpc("remove_group_member", { p_conversation_id: conversationId, p_user_id: userId });
  if (error) return { ok: false, error: friendly(error.message) };
  revalidatePath(`/messages/${conversationId}`);
  return { ok: true };
}

export async function leaveGroup(conversationId: string): Promise<{ ok: false; error: string }> {
  const supabase = await createClient();
  const { error } = await supabase.rpc("leave_group", { p_conversation_id: conversationId });
  if (error) return { ok: false, error: friendly(error.message) };
  revalidatePath("/messages");
  redirect("/messages");
}
