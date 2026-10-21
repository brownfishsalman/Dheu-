"use server";

import { revalidatePath } from "next/cache";
import { getUserId } from "@/lib/data/session";
import { createMemberInviteCode, type InviteResult } from "@/lib/invites";

// A member creates a single-use invite code for a friend.
export async function createMyInviteCode(): Promise<InviteResult> {
  const userId = await getUserId();
  if (!userId) return { ok: false, error: "Not signed in." };
  const result = await createMemberInviteCode(userId);
  if (result.ok) revalidatePath("/settings");
  return result;
}
