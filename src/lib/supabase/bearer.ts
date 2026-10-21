import "server-only";
import { createClient } from "@supabase/supabase-js";

// For API routes the Android app calls: the user's access token arrives as
// "Authorization: Bearer <jwt>" (no cookies). Returns the verified user id,
// or null when the token is missing, expired or not ours.
export async function userIdFromBearer(request: Request): Promise<string | null> {
  const header = request.headers.get("authorization") ?? "";
  if (!header.startsWith("Bearer ")) return null;
  const token = header.slice("Bearer ".length).trim();
  if (!token) return null;

  const supabase = createClient(process.env.NEXT_PUBLIC_SUPABASE_URL!, process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data } = await supabase.auth.getClaims(token);
  return (data?.claims?.sub as string | undefined) ?? null;
}
