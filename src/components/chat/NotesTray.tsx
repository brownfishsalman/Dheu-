"use client";

import Link from "next/link";
import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { Loader2, Send, Trash2 } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { Avatar } from "@/components/ui/Avatar";
import { Sheet } from "@/components/ui/Sheet";
import { timeAgo, expiresIn } from "@/lib/time";
import { NOTE_MAX_LENGTH } from "@/lib/constants";
import type { NoteAuthor, NoteItem } from "@/lib/data/notes";

type Props = { me: NoteAuthor; notes: NoteItem[] };

// Notes row at the top of the inbox: mine first (tap to write / change /
// delete), then my mutual follows' (tap to read and reply in a DM).
export function NotesTray({ me, notes }: Props) {
  const mine = notes.find((n) => n.author.id === me.id) ?? null;
  const others = notes.filter((n) => n.author.id !== me.id);
  const [editing, setEditing] = useState(false);
  const [open, setOpen] = useState<NoteItem | null>(null);

  return (
    <>
      <div className="no-scrollbar flex gap-3 overflow-x-auto border-b border-line px-4 pt-10 pb-3">
        <NoteAvatar person={me} label="Your note" text={mine?.body ?? null} onClick={() => setEditing(true)} />
        {others.map((n) => (
          <NoteAvatar key={n.author.id} person={n.author} label={n.author.username} text={n.body} onClick={() => setOpen(n)} />
        ))}
      </div>
      {editing && <NoteEditor current={mine} meId={me.id} onClose={() => setEditing(false)} />}
      {open && <NoteReply note={open} meId={me.id} onClose={() => setOpen(null)} />}
    </>
  );
}

function NoteAvatar({ person, label, text, onClick }: { person: NoteAuthor; label: string; text: string | null; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="relative flex w-[76px] shrink-0 flex-col items-center gap-1.5">
      <span
        className={`absolute -top-8 left-1/2 z-10 w-max max-w-[96px] -translate-x-1/2 rounded-2xl bg-surface px-2.5 py-1 text-left text-xs leading-tight shadow-card ring-1 ring-line ${
          text ? "text-ink" : "text-ink-faint"
        }`}
      >
        <span className="line-clamp-2 break-words">{text ?? "Share a note"}</span>
      </span>
      <Avatar path={person.avatar_path} name={person.full_name} size={64} />
      <span className="w-full truncate text-center text-xs text-ink-muted">{label}</span>
    </button>
  );
}

function NoteEditor({ current, meId, onClose }: { current: NoteItem | null; meId: string; onClose: () => void }) {
  const [text, setText] = useState(current?.body ?? "");
  const [busy, setBusy] = useState<"share" | "delete" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [, startTransition] = useTransition();
  const router = useRouter();

  function finish() {
    onClose();
    startTransition(() => router.refresh());
  }

  async function share(e: React.FormEvent) {
    e.preventDefault();
    const body = text.trim();
    if (!body || busy) return;
    setBusy("share");
    setError(null);
    const { error: err } = await createClient().rpc("set_note", { p_body: body });
    setBusy(null);
    if (err) return setError("Couldn't share your note. Try again.");
    finish();
  }

  async function remove() {
    setBusy("delete");
    setError(null);
    const { error: err } = await createClient().from("notes").delete().eq("author_id", meId);
    setBusy(null);
    if (err) return setError("Couldn't delete your note. Try again.");
    finish();
  }

  const unchanged = current !== null && text.trim() === current.body;

  return (
    <Sheet title={current ? "Your note" : "New note"} onClose={onClose}>
      <form onSubmit={share}>
        <textarea
          autoFocus
          value={text}
          onChange={(e) => setText(e.target.value)}
          maxLength={NOTE_MAX_LENGTH}
          rows={2}
          placeholder="Share a thought…"
          className="input resize-none"
        />
        <div className="mt-1 flex justify-between gap-3 text-xs text-ink-faint">
          <span>Your mutual follows can see it for 24 hours.</span>
          <span className="tabular-nums">
            {text.length}/{NOTE_MAX_LENGTH}
          </span>
        </div>
        {current && (
          <p className="mt-2 text-xs text-ink-muted">
            Shared {timeAgo(current.created_at)} ago · {expiresIn(current.expires_at)}
          </p>
        )}
        {error && <p className="mt-2 text-sm text-danger">{error}</p>}
        <div className="mt-3 flex gap-2">
          {current && (
            <button type="button" onClick={remove} disabled={busy !== null} className="btn-secondary">
              {busy === "delete" ? <Loader2 className="size-4 animate-spin" /> : <Trash2 className="size-4" />} Delete
            </button>
          )}
          <button type="submit" disabled={busy !== null || !text.trim() || unchanged} className="btn-primary flex-1">
            {busy === "share" && <Loader2 className="size-4 animate-spin" />} {current ? "Update" : "Share"}
          </button>
        </div>
      </form>
    </Sheet>
  );
}

function NoteReply({ note, meId, onClose }: { note: NoteItem; meId: string; onClose: () => void }) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [sentTo, setSentTo] = useState<string | null>(null); // conversation id
  const [error, setError] = useState<string | null>(null);
  const [, startTransition] = useTransition();
  const router = useRouter();

  async function send(e: React.FormEvent) {
    e.preventDefault();
    const body = text.trim();
    if (!body || busy) return;
    setBusy(true);
    setError(null);
    const supabase = createClient();
    const { data: conversationId, error: convErr } = await supabase.rpc("get_or_create_conversation", {
      p_other_id: note.author.id,
    });
    if (convErr || !conversationId) {
      setBusy(false);
      return setError(/blocked/i.test(convErr?.message ?? "") ? "You can't message this person." : "Couldn't send. Try again.");
    }
    // The reply keeps a copy of the note, which disappears after a day.
    const { error: msgErr } = await supabase
      .from("messages")
      .insert({ conversation_id: conversationId, sender_id: meId, body, note_text: note.body });
    setBusy(false);
    if (msgErr) {
      return setError(/blocked|violates/i.test(msgErr.message) ? "You can't message this person." : "Couldn't send. Check your connection.");
    }
    setSentTo(conversationId);
    setText("");
    startTransition(() => router.refresh());
  }

  return (
    <Sheet title={`@${note.author.username}`} onClose={onClose}>
      <div className="flex flex-col items-center gap-3 pb-2 text-center">
        <Avatar path={note.author.avatar_path} name={note.author.full_name} size={72} />
        <p className="max-w-full break-words rounded-2xl bg-surface-2 px-4 py-2.5 text-[15px]">{note.body}</p>
        <p className="text-xs text-ink-faint">
          {note.author.full_name} · {timeAgo(note.created_at)}
        </p>
      </div>
      {sentTo ? (
        <p className="mt-2 text-center text-sm text-ink-muted">
          Reply sent.{" "}
          <Link href={`/messages/${sentTo}`} className="font-semibold text-brand hover:underline">
            Open chat
          </Link>
        </p>
      ) : (
        <form onSubmit={send} className="mt-2 flex items-center gap-2">
          <input
            autoFocus
            value={text}
            onChange={(e) => setText(e.target.value)}
            maxLength={2000}
            placeholder={`Reply to ${note.author.full_name.split(" ")[0]}…`}
            className="input rounded-full"
          />
          <button type="submit" disabled={busy || !text.trim()} aria-label="Send" className="btn-primary rounded-full p-2.5">
            {busy ? <Loader2 className="size-5 animate-spin" /> : <Send className="size-5" />}
          </button>
        </form>
      )}
      {error && <p className="mt-2 text-sm text-danger">{error}</p>}
    </Sheet>
  );
}
