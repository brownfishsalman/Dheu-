import { NextResponse } from "next/server";
import { userIdFromBearer } from "@/lib/supabase/bearer";
import { createMemberInviteCode } from "@/lib/invites";

// The Android app's "Share with friends": create a single-use invite code.
// Authenticated with the member's access token (Authorization: Bearer).
export async function POST(request: Request) {
  const userId = await userIdFromBearer(request);
  if (!userId) return NextResponse.json({ ok: false, error: "Not signed in." }, { status: 401 });
  const result = await createMemberInviteCode(userId);
  return NextResponse.json(result, { status: result.ok ? 201 : 400 });
}
