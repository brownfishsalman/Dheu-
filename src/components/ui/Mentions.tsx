"use client";

import Link from "next/link";
import { useEffect, useState, type KeyboardEvent, type RefObject } from "react";
import { createClient } from "@/lib/supabase/client";
import { Avatar } from "@/components/ui/Avatar";
import { splitMentions, mentionQueryAt, insertMention } from "@/lib/mentions";
import type { Profile } from "@/lib/database.types";

export type MentionPerson = Pick<Profile, "id" | "username" | "full_name" | "avatar_path">;

const LIMIT = 6;

// Text with every @username turned into a link to that profile.
// `me` marks mentions of the viewer (highlighted in chat bubbles).
export function MentionText({ text, me, className = "font-semibold text-brand", meClassName }: {
  text: string;
  me?: string;
  className?: string;
  meClassName?: string;
}) {
  return (
    <>
      {splitMentions(text).map((p, i) =>
        "username" in p ? (
          <Link key={i} href={`/u/${p.username}`} className={p.username === me && meClassName ? meClassName : className}>
            {p.text}
          </Link>
        ) : (
          p.text
        ),
      )}
    </>
  );
}

type Field = HTMLInputElement | HTMLTextAreaElement;

// Autocomplete for an @word being typed. With `people` it suggests from that
// list (a group's members); without, it searches every member of Dheu.
export function useMentions({ value, onChange, inputRef, people }: {
  value: string;
  onChange: (value: string) => void;
  inputRef: RefObject<Field | null>;
  people?: MentionPerson[];
}) {
  const [at, setAt] = useState<{ start: number; query: string } | null>(null);
  const [found, setFound] = useState<MentionPerson[]>([]);
  const [active, setActive] = useState(0);

  // Where the caret is decides what's being typed; check after every change or move.
  function sync() {
    const el = inputRef.current;
    if (!el) return;
    setAt(mentionQueryAt(el.value, el.selectionStart ?? el.value.length));
  }

  const query = at?.query ?? null;
  useEffect(() => {
    if (!query || people) return;
    let cancelled = false;
    const t = setTimeout(async () => {
      const supabase = createClient();
      const { data } = await supabase
        .from("profiles")
        .select("id, username, full_name, avatar_path")
        .or(`username.ilike.${query}%,full_name.ilike.%${query}%`)
        .is("banned_at", null)
        .order("username")
        .limit(LIMIT);
      if (!cancelled) setFound(data ?? []);
    }, 150);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [query, people]);

  const results =
    query === null
      ? []
      : people
        ? people
            .filter((p) => p.username.startsWith(query) || p.full_name.toLowerCase().includes(query))
            .slice(0, LIMIT)
        : query
          ? found
          : []; // searching everyone starts at the first letter
  const open = results.length > 0;
  const index = Math.min(active, Math.max(0, results.length - 1));

  function pick(p: MentionPerson) {
    const el = inputRef.current;
    if (!el || !at) return;
    const next = insertMention(value, at.start, el.selectionStart ?? value.length, p.username);
    onChange(next.value);
    setAt(null);
    setActive(0);
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(next.caret, next.caret);
    });
  }

  // Arrow keys move through the list; Enter or Tab picks, Escape closes.
  function onKeyDown(e: KeyboardEvent<Field>) {
    if (!open) return;
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      setActive((index + (e.key === "ArrowDown" ? 1 : results.length - 1)) % results.length);
    } else if (e.key === "Enter" || e.key === "Tab") {
      e.preventDefault();
      pick(results[index]);
    } else if (e.key === "Escape") {
      e.preventDefault();
      setAt(null);
    }
  }

  return { open, results, index, pick, onKeyDown, sync, close: () => setAt(null) };
}

// The suggestion list, placed above (or below) the field it belongs to.
export function MentionMenu({ m, below = false }: { m: ReturnType<typeof useMentions>; below?: boolean }) {
  if (!m.open) return null;
  return (
    <ul
      role="listbox"
      aria-label="Mention someone"
      className={`absolute inset-x-0 z-30 max-h-72 overflow-y-auto rounded-xl border border-line bg-surface py-1 shadow-card ${below ? "top-full mt-1" : "bottom-full mb-1"}`}
    >
      {m.results.map((p, i) => (
        <li key={p.id} role="option" aria-selected={i === m.index}>
          <button
            type="button"
            // Keep focus in the field, so picking doesn't close the keyboard on phones.
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => m.pick(p)}
            className={`flex w-full items-center gap-3 px-3 py-2 text-left hover:bg-surface-2 ${i === m.index ? "bg-surface-2" : ""}`}
          >
            <Avatar path={p.avatar_path} name={p.full_name} size={32} />
            <span className="min-w-0">
              <span className="block truncate text-sm font-semibold">{p.username}</span>
              <span className="block truncate text-xs text-ink-muted">{p.full_name}</span>
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
