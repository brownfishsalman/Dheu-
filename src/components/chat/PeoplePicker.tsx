"use client";

import { useMemo, useState, useTransition } from "react";
import { Search, Check, Loader2, X } from "lucide-react";
import { Avatar } from "@/components/ui/Avatar";
import { createGroup, addGroupMembers } from "@/app/(app)/messages/actions";
import type { ChatPeer } from "@/lib/data/chat";
import { GROUP_MIN_OTHERS, GROUP_MAX_MEMBERS } from "@/lib/constants";

type Props =
  | { mode: "create"; people: ChatPeer[] }
  | { mode: "add"; people: ChatPeer[]; conversationId: string; roomLeft: number };

// Multi-select list of members with a search box. "create" also asks for a
// group name; "add" appends to an existing group. Both hand off to a server
// action that redirects into the group when done.
export function PeoplePicker(props: Props) {
  const [q, setQ] = useState("");
  const [title, setTitle] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  const limit = props.mode === "create" ? GROUP_MAX_MEMBERS - 1 : props.roomLeft;
  const byId = useMemo(() => new Map(props.people.map((p) => [p.id, p])), [props.people]);
  const shown = useMemo(() => {
    const s = q.trim().toLowerCase();
    if (!s) return props.people;
    return props.people.filter((p) => p.username.includes(s) || p.full_name.toLowerCase().includes(s));
  }, [q, props.people]);

  function toggle(id: string) {
    setError(null);
    if (selected.has(id)) {
      setSelected((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
      return;
    }
    if (selected.size >= limit) {
      setError(`You can add ${limit} more ${limit === 1 ? "person" : "people"} at most.`);
      return;
    }
    setSelected((prev) => new Set(prev).add(id));
  }

  const enough = props.mode === "create" ? selected.size >= GROUP_MIN_OTHERS && title.trim().length > 0 : selected.size > 0;

  function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!enough || pending) return;
    setError(null);
    const ids = [...selected];
    startTransition(async () => {
      // On success the action redirects and never resolves here.
      const res = props.mode === "create" ? await createGroup(title, ids) : await addGroupMembers(props.conversationId, ids);
      if (res && !res.ok) setError(res.error);
    });
  }

  return (
    <form onSubmit={submit} className="flex flex-col">
      {props.mode === "create" && (
        <div className="px-4 pt-3">
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={60}
            placeholder="Group name"
            autoComplete="off"
            className="input"
          />
        </div>
      )}

      {selected.size > 0 && (
        <ul className="no-scrollbar flex gap-2 overflow-x-auto px-4 pt-3" aria-label="Selected">
          {[...selected].map((id) => {
            const p = byId.get(id);
            if (!p) return null;
            return (
              <li key={id} className="shrink-0">
                <button
                  type="button"
                  onClick={() => toggle(id)}
                  aria-label={`Remove ${p.full_name}`}
                  className="flex items-center gap-1.5 rounded-full bg-brand-soft py-1 pr-2 pl-1 text-sm font-medium text-brand"
                >
                  <Avatar path={p.avatar_path} name={p.full_name} size={22} />
                  {p.full_name.split(" ")[0]}
                  <X className="size-3.5" />
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <div className="px-4 py-3">
        <label className="relative block">
          <Search className="pointer-events-none absolute inset-y-0 left-3.5 my-auto size-5 text-ink-faint" />
          <input
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search people"
            autoComplete="off"
            className="input pl-11"
          />
        </label>
      </div>

      <ul className="divide-y divide-line">
        {shown.length === 0 && <li className="px-4 py-10 text-center text-sm text-ink-muted">No one found.</li>}
        {shown.map((p) => {
          const on = selected.has(p.id);
          return (
            <li key={p.id}>
              <button
                type="button"
                onClick={() => toggle(p.id)}
                aria-pressed={on}
                className="flex w-full items-center gap-3 px-4 py-2.5 text-left hover:bg-surface-2/60"
              >
                <Avatar path={p.avatar_path} name={p.full_name} size={44} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold">{p.username}</span>
                  <span className="block truncate text-sm text-ink-muted">{p.full_name}</span>
                </span>
                <span
                  className={`flex size-6 items-center justify-center rounded-full border transition ${
                    on ? "border-brand bg-brand text-brand-ink" : "border-line"
                  }`}
                  aria-hidden
                >
                  {on && <Check className="size-4" />}
                </span>
              </button>
            </li>
          );
        })}
      </ul>

      <div className="pb-safe sticky bottom-14 border-t border-line bg-surface px-4 py-3 md:bottom-0">
        {error && <p className="mb-2 text-xs text-danger">{error}</p>}
        <button type="submit" disabled={!enough || pending} className="btn-primary w-full">
          {pending && <Loader2 className="size-4 animate-spin" />}
          {props.mode === "create"
            ? selected.size < GROUP_MIN_OTHERS
              ? `Pick ${GROUP_MIN_OTHERS - selected.size} more`
              : `Create group (${selected.size + 1})`
            : selected.size === 0
              ? "Pick people to add"
              : `Add ${selected.size} ${selected.size === 1 ? "person" : "people"}`}
        </button>
      </div>
    </form>
  );
}
