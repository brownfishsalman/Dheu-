"use client";

import Link from "next/link";
import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { UserPlus, LogOut, Pencil, Check, X, Loader2, Crown } from "lucide-react";
import { Avatar } from "@/components/ui/Avatar";
import { renameGroup, removeGroupMember, leaveGroup } from "@/app/(app)/messages/actions";
import type { ChatPeer } from "@/lib/data/chat";
import { GROUP_MAX_MEMBERS } from "@/lib/constants";

type Props = {
  conversationId: string;
  title: string;
  members: ChatPeer[]; // oldest first, includes me
  ownerId: string | null;
  meId: string;
};

export function GroupInfo({ conversationId, title, members, ownerId, meId }: Props) {
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(title);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null); // member id being removed
  const [pending, startTransition] = useTransition();
  const isOwner = ownerId === meId;
  const full = members.length >= GROUP_MAX_MEMBERS;

  function saveTitle(e: React.FormEvent) {
    e.preventDefault();
    if (draft.trim() === title) return setEditing(false);
    setError(null);
    startTransition(async () => {
      const res = await renameGroup(conversationId, draft);
      if (!res.ok) return setError(res.error);
      setEditing(false);
      router.refresh();
    });
  }

  function remove(p: ChatPeer) {
    if (!confirm(`Remove ${p.full_name} from the group?`)) return;
    setError(null);
    setBusy(p.id);
    startTransition(async () => {
      const res = await removeGroupMember(conversationId, p.id);
      setBusy(null);
      if (!res.ok) return setError(res.error);
      router.refresh();
    });
  }

  function leave() {
    if (!confirm("Leave this group? You'll stop receiving its messages.")) return;
    setError(null);
    startTransition(async () => {
      const res = await leaveGroup(conversationId); // redirects to /messages on success
      if (res && !res.ok) setError(res.error);
    });
  }

  return (
    <div className="flex flex-col">
      {/* Name */}
      <div className="px-4 py-4">
        {editing ? (
          <form onSubmit={saveTitle} className="flex items-center gap-2">
            <input
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              maxLength={60}
              autoFocus
              aria-label="Group name"
              className="input"
            />
            <button type="submit" disabled={pending} aria-label="Save name" className="btn-primary rounded-full p-2.5">
              {pending ? <Loader2 className="size-5 animate-spin" /> : <Check className="size-5" />}
            </button>
            <button
              type="button"
              onClick={() => { setDraft(title); setEditing(false); }}
              aria-label="Cancel"
              className="rounded-full p-2.5 text-ink-muted hover:bg-surface-2"
            >
              <X className="size-5" />
            </button>
          </form>
        ) : (
          <button
            type="button"
            onClick={() => setEditing(true)}
            className="group flex w-full items-center gap-2 text-left"
            aria-label="Rename group"
          >
            <span className="font-display text-xl font-semibold">{title}</span>
            <Pencil className="size-4 text-ink-faint transition group-hover:text-ink" />
          </button>
        )}
        <p className="mt-1 text-sm text-ink-muted">
          {members.length} {members.length === 1 ? "member" : "members"}
        </p>
      </div>

      {error && <p className="px-4 pb-2 text-xs text-danger">{error}</p>}

      {/* Members */}
      <div className="border-t border-line">
        {!full && (
          <Link href={`/messages/${conversationId}/add`} className="flex items-center gap-3 px-4 py-3 font-semibold text-brand hover:bg-surface-2/60">
            <span className="flex size-11 items-center justify-center rounded-full bg-brand-soft">
              <UserPlus className="size-5" />
            </span>
            Add people
          </Link>
        )}
        <ul className="divide-y divide-line">
          {members.map((p) => {
            const me = p.id === meId;
            return (
              <li key={p.id} className="flex items-center gap-3 px-4 py-2.5">
                <Link href={`/u/${p.username}`} className="flex min-w-0 flex-1 items-center gap-3">
                  <Avatar path={p.avatar_path} name={p.full_name} size={44} />
                  <span className="min-w-0">
                    <span className="flex items-center gap-1.5 truncate font-semibold">
                      {me ? "You" : p.username}
                      {p.id === ownerId && (
                        <span title="Group owner" className="text-brand">
                          <Crown className="size-3.5" aria-label="Group owner" />
                        </span>
                      )}
                    </span>
                    <span className="block truncate text-sm text-ink-muted">{p.full_name}</span>
                  </span>
                </Link>
                {isOwner && !me && (
                  <button
                    type="button"
                    onClick={() => remove(p)}
                    disabled={pending}
                    className="btn-secondary px-3 py-1.5 text-sm"
                  >
                    {busy === p.id ? <Loader2 className="size-4 animate-spin" /> : "Remove"}
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      </div>

      <div className="border-t border-line px-4 py-4">
        <button type="button" onClick={leave} disabled={pending} className="btn-danger w-full">
          <LogOut className="size-4" /> Leave group
        </button>
        {isOwner && members.length > 1 && (
          <p className="mt-2 text-center text-xs text-ink-muted">If you leave, the longest-standing member becomes the owner.</p>
        )}
      </div>
    </div>
  );
}
