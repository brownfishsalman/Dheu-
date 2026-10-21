"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Send, Loader2, ImagePlus, X, Reply, Clock, Mic, Square, Trash2 } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { ensureRealtimeAuth } from "@/lib/supabase/realtime";
import { publicUrl } from "@/lib/storage";
import { processImage, POST_MAX, ACCEPT_ATTR, newImagePath, type ProcessedImage } from "@/lib/images";
import { newAudioPath, baseMime, formatDuration } from "@/lib/audio";
import { Avatar } from "@/components/ui/Avatar";
import { GroupAvatar } from "@/components/chat/GroupAvatar";
import { VoiceNote, useVoiceRecorder } from "@/components/chat/VoiceNote";
import { loadOlderMessages } from "@/app/(app)/messages/actions";
import type { ChatMessage, ChatPeer, ReplyPreview } from "@/lib/data/chat";
import { eventPeople } from "@/lib/chat-events";
import type { BlockStatus } from "@/lib/data/profiles";
import { MESSAGE_PAGE, CHAT_PHOTO_DAYS, CHAT_VOICE_DAYS, VOICE_MAX_SECONDS } from "@/lib/constants";

type Props = {
  conversationId: string;
  meId: string;
  peer: ChatPeer | null; // the other person in a DM; null for groups
  group: { title: string; memberCount: number } | null;
  members: ChatPeer[]; // everyone whose name may appear (current members + people who left)
  initialMessages: ChatMessage[];
  blocked: BlockStatus;
};

const FORMER = "Former member";

function sameDay(a: string, b: string) {
  return new Date(a).toDateString() === new Date(b).toDateString();
}
function dayLabel(iso: string) {
  const d = new Date(iso);
  const today = new Date();
  if (d.toDateString() === today.toDateString()) return "Today";
  const y = new Date(today);
  y.setDate(today.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return "Yesterday";
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: d.getFullYear() !== today.getFullYear() ? "numeric" : undefined });
}
function clock(iso: string) {
  return new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
}
function daysLeft(iso: string) {
  return Math.max(0, Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000));
}
// One-line label for a quoted message, valid even after its media expired.
function replyLabel(r: Pick<ReplyPreview, "body" | "image_path" | "image_expires_at" | "audio_path" | "audio_expires_at">) {
  if (r.audio_path || r.audio_expires_at) return "🎤 Voice message";
  if (r.image_path || r.image_expires_at) return "📷 Photo";
  return r.body;
}
function toReplyPreview(m: ChatMessage): ReplyPreview {
  return {
    id: m.id,
    body: m.body,
    sender_id: m.sender_id,
    image_path: m.image_path,
    image_expires_at: m.image_expires_at,
    audio_path: m.audio_path,
    audio_expires_at: m.audio_expires_at,
  };
}
const REPLY_SELECT = "id, body, sender_id, image_path, image_expires_at, audio_path, audio_expires_at";

// "You added Rafi Karim, Tania Sultana" — rendered from the event so the
// viewer's own name becomes "You"; the stored body is the fallback.
function systemText(m: ChatMessage, nameOf: (id: string) => string): string {
  const e = m.event;
  if (!e) return m.body;
  const actor = nameOf(e.actor);
  const targets = (e.targets ?? []).map(nameOf).join(", ");
  switch (e.type) {
    case "created":
      return `${actor} created the group`;
    case "added":
      return `${actor} added ${targets || "someone"}`;
    case "removed":
      return `${actor} removed ${targets || "someone"}`;
    case "left":
      return `${actor} left`;
    case "renamed":
      return `${actor} renamed the group to “${e.title ?? ""}”`;
    default:
      return m.body;
  }
}

export function ChatThread({ conversationId, meId, peer, group, members, initialMessages, blocked }: Props) {
  const isGroup = group !== null;
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages);
  // Names and avatars by user id; grows if someone unknown (e.g. just added) posts.
  const [people, setPeople] = useState(() => new Map(members.map((p) => [p.id, p])));
  const nameOf = useCallback(
    (id: string) => (id === meId ? "You" : (people.get(id)?.full_name ?? FORMER)),
    [meId, people],
  );
  const [text, setText] = useState("");
  const [photo, setPhoto] = useState<ProcessedImage | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null);
  const [sending, setSending] = useState(false);
  const [hasOlder, setHasOlder] = useState(initialMessages.length >= MESSAGE_PAGE);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lightbox, setLightbox] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const rec = useVoiceRecorder(VOICE_MAX_SECONDS);
  const clip = rec.clip;

  const markRead = useCallback(async () => {
    const supabase = createClient();
    await supabase.rpc("mark_conversation_read", { p_conversation_id: conversationId }); // server clock
  }, [conversationId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView();
    markRead();
  }, [markRead]);

  // Latest messages / people, readable from inside the realtime callback
  const messagesRef = useRef(messages);
  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);
  const peopleRef = useRef(people);
  useEffect(() => {
    peopleRef.current = people;
  }, [people]);

  // Realtime: new messages in this conversation
  useEffect(() => {
    const supabase = createClient();
    let cancelled = false;
    const channel = supabase.channel(`conversation:${conversationId}:${Math.random().toString(36).slice(2)}`);
    channel.on(
      "postgres_changes",
      { event: "INSERT", schema: "public", table: "messages", filter: `conversation_id=eq.${conversationId}` },
      async (payload) => {
        const row = payload.new as Omit<ChatMessage, "story" | "reply">;
        // Learn anyone new this message names: the sender, or the people a
        // system line mentions ("Salman added Rafi").
        const unknown = [...new Set(eventPeople(row))].filter((id) => id !== meId && !peopleRef.current.has(id));
        if (unknown.length > 0) {
          const { data: ps } = await supabase.from("profiles").select("id, username, full_name, avatar_path").in("id", unknown);
          if (ps && ps.length > 0) {
            setPeople((prev) => {
              const next = new Map(prev);
              for (const p of ps) next.set(p.id, p);
              return next;
            });
          }
        }
        let story: ChatMessage["story"] = null;
        if (row.story_id) {
          const { data } = await supabase.from("stories").select("image_path, author_id").eq("id", row.story_id).maybeSingle();
          story = data ?? null;
        }
        let reply: ReplyPreview | null = null;
        if (row.reply_to_id) {
          const local = messagesRef.current.find((m) => m.id === row.reply_to_id);
          if (local) reply = toReplyPreview(local);
          else {
            const { data } = await supabase.from("messages").select(REPLY_SELECT).eq("id", row.reply_to_id).maybeSingle();
            reply = data ?? null;
          }
        }
        setMessages((m) => (m.some((x) => x.id === row.id) ? m : [...m, { ...row, story, reply }]));
        if (row.sender_id !== meId && document.visibilityState === "visible") markRead();
      },
    );
    ensureRealtimeAuth(supabase).then(() => {
      if (cancelled) return;
      channel.subscribe((status, err) => {
        if (status === "CHANNEL_ERROR" || status === "TIMED_OUT") console.warn("[chat realtime]", status, err?.message ?? "");
      });
    });
    return () => {
      cancelled = true;
      supabase.removeChannel(channel);
    };
  }, [conversationId, meId, markRead]);

  // Keep pinned to the bottom when new messages arrive (if already near the bottom)
  const lastId = messages[messages.length - 1]?.id;
  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 200;
    if (nearBottom) bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [lastId]);

  async function pickPhoto(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setError(null);
    setPreparing(true);
    try {
      if (photo) URL.revokeObjectURL(photo.previewUrl);
      setPhoto(await processImage(file, POST_MAX));
      inputRef.current?.focus();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't read that photo.");
    } finally {
      setPreparing(false);
    }
  }

  const startReply = useCallback((m: ChatMessage) => {
    setReplyTo(m);
    inputRef.current?.focus();
  }, []);

  async function send(e: React.FormEvent) {
    e.preventDefault();
    const body = text.trim();
    if ((!body && !photo && !clip) || sending) return;
    setSending(true);
    setError(null);
    const supabase = createClient();

    let image_path: string | null = null;
    if (photo) {
      image_path = newImagePath(meId);
      const { error: upErr } = await supabase.storage
        .from("chat")
        .upload(image_path, photo.blob, { contentType: "image/jpeg", cacheControl: "604800" });
      if (upErr) {
        setSending(false);
        return setError("Couldn't upload the photo.");
      }
    }

    let audio_path: string | null = null;
    let audio_duration_ms: number | null = null;
    if (clip) {
      audio_path = newAudioPath(meId, clip.mime);
      audio_duration_ms = Math.round(clip.durationMs);
      const { error: upErr } = await supabase.storage
        .from("chat")
        .upload(audio_path, clip.blob, { contentType: baseMime(clip.mime), cacheControl: "604800" });
      if (upErr) {
        setSending(false);
        return setError("Couldn't upload the voice message.");
      }
    }

    // Media expiry (image_expires_at / audio_expires_at) is stamped by the database.
    const { data, error: err } = await supabase
      .from("messages")
      .insert({
        conversation_id: conversationId,
        sender_id: meId,
        body,
        image_path,
        audio_path,
        audio_duration_ms,
        reply_to_id: replyTo?.id ?? null,
      })
      .select("id, conversation_id, sender_id, body, story_id, image_path, image_expires_at, audio_path, audio_duration_ms, audio_expires_at, reply_to_id, kind, event, created_at")
      .single();
    setSending(false);
    if (err || !data) {
      const uploaded = [image_path, audio_path].filter((p): p is string => Boolean(p));
      if (uploaded.length) await supabase.storage.from("chat").remove(uploaded);
      return setError(/blocked|violates/i.test(err?.message ?? "") ? "You can't message this person." : "Couldn't send. Check your connection.");
    }
    const reply = replyTo ? toReplyPreview(replyTo) : null;
    // Members can only insert kind = 'text', so event is always null here.
    setMessages((m) => (m.some((x) => x.id === data.id) ? m : [...m, { ...data, event: null, story: null, reply }]));
    setText("");
    setReplyTo(null);
    if (photo) URL.revokeObjectURL(photo.previewUrl);
    setPhoto(null);
    rec.reset();
    inputRef.current?.focus();
    markRead();
    requestAnimationFrame(() => bottomRef.current?.scrollIntoView({ behavior: "smooth" }));
  }

  async function loadOlder() {
    if (loadingOlder || messages.length === 0) return;
    setLoadingOlder(true);
    const el = listRef.current;
    const prevHeight = el?.scrollHeight ?? 0;
    const older = await loadOlderMessages(conversationId, messages[0].created_at);
    setMessages((m) => [...older, ...m]);
    setHasOlder(older.length >= MESSAGE_PAGE);
    setLoadingOlder(false);
    requestAnimationFrame(() => {
      if (el) el.scrollTop = el.scrollHeight - prevHeight;
    });
  }

  const canSend = blocked === null;

  // Typing in the composer re-renders this component on every keystroke. Build
  // the (possibly long) message list only when its inputs change, so React can
  // skip it entirely while someone types.
  const list = useMemo(() => {
    const byId = new Map(messages.map((m) => [m.id, m]));
    return messages.map((m, i) => {
      const mine = m.sender_id === meId;
      const prev = messages[i - 1];
      const next = messages[i + 1];
      const showDay = !prev || !sameDay(prev.created_at, m.created_at);
      if (m.kind === "system") {
        return (
          <div key={m.id}>
            {showDay && <div className="my-3 text-center text-xs font-medium text-ink-faint">{dayLabel(m.created_at)}</div>}
            <p className="my-2 px-6 text-center text-xs text-ink-faint" title={clock(m.created_at)}>
              {systemText(m, nameOf)}
            </p>
          </div>
        );
      }
      // Consecutive bubbles from one person within 5 minutes form a run; a
      // day change or a system line breaks it.
      const grouped = prev && prev.kind !== "system" && prev.sender_id === m.sender_id && !showDay &&
        new Date(m.created_at).getTime() - new Date(prev.created_at).getTime() < 5 * 60_000;
      // In groups: name above the first bubble of a run, avatar beside the last.
      const endOfRun = !next || next.kind === "system" || next.sender_id !== m.sender_id || !sameDay(m.created_at, next.created_at) ||
        new Date(next.created_at).getTime() - new Date(m.created_at).getTime() >= 5 * 60_000;
      return (
        <div key={m.id}>
          {showDay && <div className="my-3 text-center text-xs font-medium text-ink-faint">{dayLabel(m.created_at)}</div>}
          <Bubble
            m={m}
            mine={mine}
            grouped={grouped}
            sender={isGroup && !mine ? { name: nameOf(m.sender_id), avatar: people.get(m.sender_id) ?? null, showName: !grouped, showAvatar: endOfRun } : null}
            nameOf={nameOf}
            onReply={canSend ? () => startReply(m) : undefined}
            onOpenImage={setLightbox}
            scrollToReply={(id) => byId.has(id) && document.getElementById(`msg-${id}`)?.scrollIntoView({ behavior: "smooth", block: "center" })}
          />
        </div>
      );
    });
  }, [messages, meId, isGroup, people, nameOf, canSend, startReply]);

  return (
    <div className="flex h-[calc(100dvh-3.5rem-3rem-3.5rem-env(safe-area-inset-bottom))] flex-col md:h-[calc(100dvh-3.5rem)]">
      <div ref={listRef} className="flex-1 overflow-y-auto px-3 py-3">
        {hasOlder && (
          <div className="mb-3 flex justify-center">
            <button type="button" onClick={loadOlder} disabled={loadingOlder} className="btn-ghost text-sm">
              {loadingOlder ? <Loader2 className="size-4 animate-spin" /> : "Load earlier messages"}
            </button>
          </div>
        )}

        {messages.length === 0 && (
          <div className="flex flex-col items-center gap-2 py-16 text-center">
            {group ? (
              <>
                <GroupAvatar members={members.filter((p) => p.id !== meId)} size={72} />
                <p className="font-semibold">{group.title}</p>
                <p className="text-sm text-ink-muted">{group.memberCount} members · Say hi 👋</p>
              </>
            ) : peer ? (
              <>
                <Avatar path={peer.avatar_path} name={peer.full_name} size={72} />
                <p className="font-semibold">{peer.full_name}</p>
                <p className="text-sm text-ink-muted">@{peer.username} · Say hi 👋</p>
              </>
            ) : null}
          </div>
        )}

        {list}
        <div ref={bottomRef} />
      </div>

      {!canSend ? (
        <p className="border-t border-line bg-surface px-4 py-4 text-center text-sm text-ink-muted">
          {blocked === "by_me" || blocked === "both" ? "You blocked this member. Unblock them in Settings to message again." : "You can't message this member."}
        </p>
      ) : (
        <form onSubmit={send} className="border-t border-line bg-surface">
          {replyTo && (
            <div className="flex items-center gap-2 border-l-4 border-brand bg-surface-2/60 px-3 py-2 text-sm">
              <Reply className="size-4 shrink-0 text-brand" />
              <div className="min-w-0 flex-1">
                <p className="text-xs font-semibold text-brand">{nameOf(replyTo.sender_id)}</p>
                <p className="truncate text-ink-muted">{replyLabel(replyTo)}</p>
              </div>
              <button type="button" onClick={() => setReplyTo(null)} aria-label="Cancel reply" className="rounded-full p-1 text-ink-muted hover:bg-surface-2">
                <X className="size-4" />
              </button>
            </div>
          )}
          {photo && (
            <div className="flex items-center gap-3 px-3 pt-2">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={photo.previewUrl} alt="" className="h-20 w-20 rounded-lg object-cover" />
              <p className="flex-1 text-xs text-ink-muted">
                <Clock className="mr-1 inline size-3.5" />
                Disappears {CHAT_PHOTO_DAYS} days after sending.
              </p>
              <button type="button" onClick={() => { URL.revokeObjectURL(photo.previewUrl); setPhoto(null); }} aria-label="Remove photo" className="rounded-full p-1.5 text-ink-muted hover:bg-surface-2">
                <X className="size-4" />
              </button>
            </div>
          )}
          {clip && (
            <div className="px-3 pt-2">
              <div className="flex items-center gap-2">
                <VoiceNote src={clip.previewUrl} durationMs={clip.durationMs} className="min-w-0 flex-1 rounded-xl bg-surface-2 px-3 py-1.5 text-ink" />
                <button type="button" onClick={rec.reset} aria-label="Remove voice message" className="rounded-full p-1.5 text-ink-muted hover:bg-surface-2">
                  <X className="size-4" />
                </button>
              </div>
              <p className="mt-1 text-xs text-ink-muted">
                <Clock className="mr-1 inline size-3.5" />
                Disappears {CHAT_VOICE_DAYS} days after sending.
              </p>
            </div>
          )}
          {rec.recording ? (
            <div className="flex items-center gap-3 px-3 py-2">
              <button
                type="button"
                onClick={rec.cancel}
                aria-label="Discard recording"
                className="rounded-full p-2 text-ink-muted hover:bg-surface-2 hover:text-danger"
              >
                <Trash2 className="size-5" />
              </button>
              <span className="size-2.5 shrink-0 animate-pulse rounded-full bg-danger" aria-hidden />
              <span className="flex-1 text-sm tabular-nums" aria-live="polite">
                {formatDuration(rec.elapsedMs)}
                <span className="text-ink-faint"> / {formatDuration(VOICE_MAX_SECONDS * 1000)}</span>
              </span>
              <button type="button" onClick={rec.stop} aria-label="Stop recording" className="btn-primary rounded-full p-2.5">
                <Square className="size-5 fill-current" />
              </button>
            </div>
          ) : (
            <div className="flex items-center gap-2 px-3 py-2">
              <button
                type="button"
                onClick={() => fileRef.current?.click()}
                disabled={preparing || sending || Boolean(clip)}
                aria-label="Send a photo"
                className="rounded-full p-2 text-ink-muted hover:bg-surface-2 hover:text-ink disabled:opacity-50"
              >
                {preparing ? <Loader2 className="size-5 animate-spin" /> : <ImagePlus className="size-5" />}
              </button>
              <input ref={fileRef} type="file" accept={ACCEPT_ATTR} className="hidden" onChange={pickPhoto} />
              <input
                ref={inputRef}
                value={text}
                onChange={(e) => setText(e.target.value)}
                maxLength={2000}
                placeholder={photo || clip ? "Add a caption…" : "Message…"}
                autoComplete="off"
                enterKeyHint="send"
                className="input rounded-full"
              />
              {/* Mic until there's something to send, then the send button (WhatsApp-style) */}
              {!text.trim() && !photo && !clip && rec.supported ? (
                <button
                  type="button"
                  onClick={rec.start}
                  disabled={sending || preparing}
                  aria-label="Record a voice message"
                  className="btn-primary rounded-full p-2.5"
                >
                  <Mic className="size-5" />
                </button>
              ) : (
                <button
                  type="submit"
                  disabled={sending || (!text.trim() && !photo && !clip)}
                  aria-label="Send"
                  className="btn-primary rounded-full p-2.5"
                >
                  {sending ? <Loader2 className="size-5 animate-spin" /> : <Send className="size-5" />}
                </button>
              )}
            </div>
          )}
          {(error || rec.error) && <p className="px-4 pb-2 text-xs text-danger">{error ?? rec.error}</p>}
        </form>
      )}

      {lightbox && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/90 p-4" onClick={() => setLightbox(null)}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={lightbox} alt="" className="max-h-full max-w-full rounded-lg object-contain" />
          <button type="button" aria-label="Close" className="absolute top-4 right-4 rounded-full bg-white/15 p-2 text-white">
            <X className="size-6" />
          </button>
        </div>
      )}
    </div>
  );
}

// One message. Swipe horizontally (touch) or hover → reply icon (mouse) to reply.
type SenderInfo = { name: string; avatar: ChatPeer | null; showName: boolean; showAvatar: boolean };

function Bubble({
  m,
  mine,
  grouped,
  sender,
  nameOf,
  onReply,
  onOpenImage,
  scrollToReply,
}: {
  m: ChatMessage;
  mine: boolean;
  grouped: boolean;
  sender: SenderInfo | null; // set for other people's messages in a group
  nameOf: (id: string) => string;
  onReply?: () => void;
  onOpenImage: (src: string) => void;
  scrollToReply: (id: string) => void;
}) {
  const [dx, setDx] = useState(0);
  const start = useRef<{ x: number; y: number; id: number } | null>(null);
  const fired = useRef(false);
  const THRESHOLD = 56;

  function onPointerDown(e: React.PointerEvent) {
    if (e.pointerType === "mouse" || !onReply) return;
    start.current = { x: e.clientX, y: e.clientY, id: e.pointerId };
    fired.current = false;
  }
  function onPointerMove(e: React.PointerEvent) {
    const s = start.current;
    if (!s || e.pointerId !== s.id) return;
    const ddx = e.clientX - s.x;
    const ddy = e.clientY - s.y;
    if (Math.abs(ddy) > Math.abs(ddx) + 8) return; // vertical scroll wins
    // Swipe toward the centre: right for their bubbles, left for mine
    const dir = mine ? -1 : 1;
    const amount = Math.max(0, ddx * dir);
    setDx(Math.min(amount, 90) * dir);
    if (amount >= THRESHOLD && !fired.current) {
      fired.current = true;
      if (navigator.vibrate) navigator.vibrate(10);
    }
  }
  function onPointerEnd() {
    if (fired.current && onReply) onReply();
    start.current = null;
    setDx(0);
  }

  const storySrc = m.story ? publicUrl("stories", m.story.image_path) : null;
  const imgSrc = m.image_path ? publicUrl("chat", m.image_path) : null;
  const photoExpired = !m.image_path && m.image_expires_at !== null;
  const audioSrc = m.audio_path ? publicUrl("chat", m.audio_path) : null;
  const voiceExpired = !m.audio_path && m.audio_expires_at !== null;
  const mediaExpiresAt = imgSrc ? m.image_expires_at : audioSrc ? m.audio_expires_at : null;

  return (
    <div
      id={`msg-${m.id}`}
      className={`group relative flex touch-pan-y select-none ${mine ? "justify-end" : "justify-start"} ${grouped ? "mt-0.5" : "mt-2"}`}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerEnd}
      onPointerCancel={onPointerEnd}
      style={{ transform: dx ? `translateX(${dx}px)` : undefined, transition: dx ? "none" : "transform 150ms ease-out" }}
    >
      {/* reply hint revealed by the swipe / on hover */}
      {onReply && (
        <button
          type="button"
          onClick={onReply}
          aria-label="Reply"
          className={`absolute top-1/2 -translate-y-1/2 rounded-full p-1.5 text-ink-faint transition hover:bg-surface-2 hover:text-ink ${
            mine ? "right-full mr-1" : "left-full ml-1"
          } ${Math.abs(dx) >= THRESHOLD ? "text-brand opacity-100" : "opacity-0 group-hover:opacity-100"}`}
        >
          <Reply className="size-4" />
        </button>
      )}

      {/* Group chats: the sender's avatar beside the last bubble of their run (a spacer otherwise) */}
      {sender && (
        <span className="mr-2 w-7 shrink-0 self-end">
          {sender.showAvatar && (
            <Avatar path={sender.avatar?.avatar_path} name={sender.avatar?.full_name ?? sender.name} size={28} />
          )}
        </span>
      )}

      <div className={`max-w-[78%] ${mine ? "items-end" : "items-start"} flex flex-col`}>
        {sender?.showName && <span className="mb-0.5 ml-1 text-xs font-semibold text-ink-muted">{sender.name}</span>}
        {m.story_id && (
          <div className={`mb-1 flex items-end gap-2 text-xs text-ink-faint ${mine ? "flex-row-reverse" : ""}`}>
            {storySrc ? (
              <Link href={`/stories/${m.story!.author_id}`} className="block h-20 w-12 overflow-hidden rounded-lg border border-line bg-surface-2">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={storySrc} alt="" className="h-full w-full object-cover" />
              </Link>
            ) : (
              <span className="flex h-20 w-12 items-center justify-center rounded-lg border border-dashed border-line text-center text-[10px] leading-tight">story expired</span>
            )}
            <span>{mine ? "You replied to their story" : "Replied to your story"}</span>
          </div>
        )}

        <div
          title={clock(m.created_at)}
          className={`overflow-hidden rounded-2xl text-[15px] ${mine ? "rounded-br-md bg-brand text-brand-ink" : "rounded-bl-md bg-surface-2 text-ink"}`}
        >
          {m.reply && (
            <button
              type="button"
              onClick={() => scrollToReply(m.reply!.id)}
              className={`mx-2 mt-2 block w-[calc(100%-1rem)] rounded-lg border-l-2 px-2.5 py-1.5 text-left text-xs ${
                mine ? "border-brand-ink/60 bg-black/10" : "border-brand bg-surface"
              }`}
            >
              <span className={`block font-semibold ${mine ? "opacity-90" : "text-brand"}`}>
                {nameOf(m.reply.sender_id)}
              </span>
              <span className="line-clamp-2 opacity-80">{replyLabel(m.reply)}</span>
            </button>
          )}

          {imgSrc && (
            <button type="button" onClick={() => onOpenImage(imgSrc)} className="block" aria-label="Open photo">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={imgSrc} alt="" loading="lazy" className="max-h-80 w-auto max-w-full object-cover" draggable={false} />
            </button>
          )}
          {photoExpired && (
            <p className="flex items-center gap-1.5 px-3.5 py-2 text-sm italic opacity-70">
              <Clock className="size-3.5" /> Photo expired
            </p>
          )}
          {audioSrc && (
            <VoiceNote src={audioSrc} durationMs={m.audio_duration_ms} mine={mine} className="w-64 max-w-full px-3 py-2" />
          )}
          {voiceExpired && (
            <p className="flex items-center gap-1.5 px-3.5 py-2 text-sm italic opacity-70">
              <Clock className="size-3.5" /> Voice message expired
            </p>
          )}
          {m.body && <p className="whitespace-pre-wrap break-words px-3.5 py-2">{m.body}</p>}
          {mediaExpiresAt && (
            <p className={`px-3 pb-1.5 text-[10px] ${mine ? "text-brand-ink/70" : "text-ink-faint"} ${m.body ? "-mt-1" : "pt-1"}`}>
              disappears in {daysLeft(mediaExpiresAt)}d
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
