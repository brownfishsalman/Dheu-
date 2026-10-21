"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { Loader2, MoreHorizontal, Trash2 } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { BADGES_REFRESH_EVENT } from "@/components/nav/BadgeProvider";

type Props = { conversationId: string; title: string; isGroup: boolean };

// "⋯" on an inbox row. Delete chat clears it for me only (delete_chat_for_me);
// the others keep theirs, and a new message brings it back.
export function ConversationMenu({ conversationId, title, isGroup }: Props) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [, startTransition] = useTransition();
  const router = useRouter();

  async function deleteChat() {
    setOpen(false);
    const question = isGroup
      ? `Delete "${title}"? Its messages are cleared for you and it leaves your inbox until someone writes again. You stay in the group.`
      : `Delete your chat with ${title}? Its messages are cleared for you only; ${title} keeps their copy.`;
    if (!confirm(question)) return;
    setBusy(true);
    const { error } = await createClient().rpc("delete_chat_for_me", { p_conversation_id: conversationId });
    setBusy(false);
    if (error) return alert("Couldn't delete the chat. Try again.");
    window.dispatchEvent(new Event(BADGES_REFRESH_EVENT));
    startTransition(() => router.refresh());
  }

  return (
    <div className="relative shrink-0">
      <button
        type="button"
        aria-label={`Options for ${title}`}
        onClick={() => setOpen((o) => !o)}
        disabled={busy}
        className="rounded-full p-2 text-ink-muted hover:bg-surface-2 hover:text-ink"
      >
        {busy ? <Loader2 className="size-5 animate-spin" /> : <MoreHorizontal className="size-5" />}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute right-0 z-20 mt-1 w-44 overflow-hidden rounded-xl border border-line bg-surface py-1 shadow-card">
            <button
              type="button"
              onClick={deleteChat}
              className="flex w-full items-center gap-2.5 px-3.5 py-2 text-sm text-danger hover:bg-surface-2"
            >
              <Trash2 className="size-4" /> Delete chat
            </button>
          </div>
        </>
      )}
    </div>
  );
}
