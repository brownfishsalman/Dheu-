"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { createClient } from "@/lib/supabase/client";
import type { FollowState } from "@/lib/data/profiles";

type Props = {
  targetId: string;
  initialState: FollowState;
  size?: "sm" | "md";
  className?: string;
};

// Follow is instant for public accounts and a request for private ones
// (follow_user decides). Requested → tap again to cancel.
export function FollowButton({ targetId, initialState, size = "md", className = "" }: Props) {
  const [state, setState] = useState<FollowState>(initialState);
  const [busy, setBusy] = useState(false);
  const [pending, startTransition] = useTransition();
  const router = useRouter();

  async function act() {
    const supabase = createClient();
    const {
      data: { session },
    } = await supabase.auth.getSession();
    const me = session?.user.id;
    if (!me) return;

    if (state === "following" && !confirm("Unfollow? If their account is private, you'll need to send a new request to follow them again.")) {
      return;
    }

    setBusy(true);
    let next: FollowState = state;
    let failed = false;
    if (state === "none") {
      const { data, error } = await supabase.rpc("follow_user", { p_target: targetId });
      failed = Boolean(error) || !data;
      if (data) next = data;
    } else if (state === "requested") {
      const { error } = await supabase.from("follow_requests").delete().match({ requester_id: me, target_id: targetId });
      failed = Boolean(error);
      next = "none";
    } else {
      const { error } = await supabase.from("follows").delete().match({ follower_id: me, following_id: targetId });
      failed = Boolean(error);
      next = "none";
    }
    setBusy(false);

    if (failed) return;
    setState(next);
    startTransition(() => router.refresh());
  }

  const sizing = size === "sm" ? "px-3 py-1.5 text-sm" : "";
  const look = state === "none" ? "btn-primary" : "btn-secondary";
  const label = state === "none" ? "Follow" : state === "requested" ? "Requested" : "Following";

  return (
    <button type="button" onClick={act} disabled={busy || pending} className={`${look} ${sizing} ${className}`}>
      {label}
    </button>
  );
}
