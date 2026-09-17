"use client";

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Play, Pause } from "lucide-react";
import { canRecordAudio, pickRecordingMime, formatDuration } from "@/lib/audio";

const subscribeNever = () => () => {};

// ---------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------

// Only one voice note plays at a time, like every chat app.
let active: HTMLAudioElement | null = null;

const BAR_COUNT = 28;

// Static "waveform" from the file path, so the same note always looks the
// same without decoding the audio.
function barsFor(seed: string): number[] {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) h = Math.imul(h ^ seed.charCodeAt(i), 16777619);
  const out: number[] = [];
  for (let i = 0; i < BAR_COUNT; i++) {
    h = Math.imul(h ^ (h >>> 15), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    out.push(0.3 + ((h >>> 0) % 1000) / 1000 * 0.7);
  }
  return out;
}

type PlayerProps = {
  src: string;
  durationMs: number | null; // from the DB; webm blobs often report Infinity to <audio>
  mine?: boolean; // colours: inside my (teal) bubble or theirs
  className?: string;
};

export function VoiceNote({ src, durationMs, mine = false, className = "" }: PlayerProps) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const [playing, setPlaying] = useState(false);
  const [positionMs, setPositionMs] = useState(0);
  const [metaMs, setMetaMs] = useState<number | null>(null);
  const totalMs = durationMs ?? metaMs;
  const bars = barsFor(src);

  useEffect(() => {
    const el = audioRef.current;
    if (!el) return;
    const onTime = () => setPositionMs(el.currentTime * 1000);
    const onMeta = () => {
      if (Number.isFinite(el.duration)) setMetaMs(el.duration * 1000);
    };
    const onPlay = () => setPlaying(true);
    const onPause = () => setPlaying(false);
    const onEnded = () => {
      setPlaying(false);
      setPositionMs(0);
    };
    el.addEventListener("timeupdate", onTime);
    el.addEventListener("loadedmetadata", onMeta);
    el.addEventListener("durationchange", onMeta);
    el.addEventListener("play", onPlay);
    el.addEventListener("pause", onPause);
    el.addEventListener("ended", onEnded);
    return () => {
      el.removeEventListener("timeupdate", onTime);
      el.removeEventListener("loadedmetadata", onMeta);
      el.removeEventListener("durationchange", onMeta);
      el.removeEventListener("play", onPlay);
      el.removeEventListener("pause", onPause);
      el.removeEventListener("ended", onEnded);
      if (active === el) active = null;
    };
  }, []);

  function toggle() {
    const el = audioRef.current;
    if (!el) return;
    if (playing) return el.pause();
    if (active && active !== el) active.pause();
    active = el;
    el.play().catch(() => setPlaying(false));
  }

  function seek(e: React.MouseEvent<HTMLDivElement>) {
    const el = audioRef.current;
    if (!el || !totalMs) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const frac = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    el.currentTime = (frac * totalMs) / 1000;
    setPositionMs(frac * totalMs);
  }

  const frac = totalMs ? Math.min(1, positionMs / totalMs) : 0;
  const label = formatDuration(playing || positionMs > 0 ? positionMs : (totalMs ?? 0));

  return (
    <div className={`flex items-center gap-2.5 ${className}`}>
      <audio ref={audioRef} src={src} preload="metadata" />
      <button
        type="button"
        onClick={toggle}
        aria-label={playing ? "Pause voice message" : "Play voice message"}
        className={`flex size-9 shrink-0 items-center justify-center rounded-full transition active:scale-95 ${
          mine ? "bg-brand-ink/20 text-brand-ink hover:bg-brand-ink/30" : "bg-brand text-brand-ink hover:bg-brand-strong"
        }`}
      >
        {playing ? <Pause className="size-4 fill-current" /> : <Play className="ml-0.5 size-4 fill-current" />}
      </button>
      <div
        role="slider"
        aria-label="Position"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(frac * 100)}
        onClick={seek}
        className="flex h-8 flex-1 cursor-pointer items-center gap-[2px]"
      >
        {bars.map((h, i) => {
          const played = (i + 0.5) / BAR_COUNT <= frac;
          return (
            <span
              key={i}
              className={`w-[3px] flex-1 rounded-full bg-current transition-opacity ${played ? "opacity-100" : "opacity-35"}`}
              style={{ height: `${Math.round(h * 100)}%` }}
            />
          );
        })}
      </div>
      <span className="w-9 shrink-0 text-right text-xs tabular-nums opacity-80">{label}</span>
    </div>
  );
}

// ---------------------------------------------------------------------
// Recorder
// ---------------------------------------------------------------------

export type VoiceClip = {
  blob: Blob;
  mime: string; // as produced by MediaRecorder (may carry ";codecs=…")
  durationMs: number;
  previewUrl: string; // object URL; released by reset()
};

const MIN_MS = 1000;

export function useVoiceRecorder(maxSeconds: number) {
  const [recording, setRecording] = useState(false);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [clip, setClip] = useState<VoiceClip | null>(null);
  const [error, setError] = useState<string | null>(null);

  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const startedAtRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const discardRef = useRef(false);
  const clipRef = useRef<VoiceClip | null>(null);

  // false on the server and during hydration, the real answer after — no mismatch.
  const supported = useSyncExternalStore(subscribeNever, canRecordAudio, () => false);

  const releaseStream = useCallback(() => {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    if (timerRef.current) clearInterval(timerRef.current);
    timerRef.current = null;
  }, []);

  const stop = useCallback(() => {
    const r = recorderRef.current;
    if (r && r.state !== "inactive") r.stop();
    else releaseStream();
  }, [releaseStream]);

  const cancel = useCallback(() => {
    discardRef.current = true;
    stop();
  }, [stop]);

  const reset = useCallback(() => {
    if (clipRef.current) URL.revokeObjectURL(clipRef.current.previewUrl);
    clipRef.current = null;
    setClip(null);
    setError(null);
  }, []);

  const start = useCallback(async () => {
    setError(null);
    if (!supported) return setError("This browser can't record audio.");
    reset();

    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (err) {
      const name = (err as DOMException)?.name;
      return setError(
        name === "NotAllowedError" || name === "SecurityError"
          ? "Microphone access was blocked. Allow it in your browser settings to send voice messages."
          : "Couldn't access the microphone.",
      );
    }

    const mime = pickRecordingMime();
    let recorder: MediaRecorder;
    try {
      recorder = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
    } catch {
      stream.getTracks().forEach((t) => t.stop());
      return setError("This browser can't record audio.");
    }

    streamRef.current = stream;
    recorderRef.current = recorder;
    chunksRef.current = [];
    discardRef.current = false;

    recorder.ondataavailable = (e) => {
      if (e.data.size > 0) chunksRef.current.push(e.data);
    };
    recorder.onstop = () => {
      const durationMs = Date.now() - startedAtRef.current;
      const type = recorder.mimeType || mime || "audio/webm";
      const blob = new Blob(chunksRef.current, { type });
      chunksRef.current = [];
      recorderRef.current = null;
      releaseStream();
      setRecording(false);
      setElapsedMs(0);

      if (discardRef.current) return;
      if (durationMs < MIN_MS || blob.size === 0) {
        setError("Too short — record for at least a second.");
        return;
      }
      const next: VoiceClip = { blob, mime: type, durationMs, previewUrl: URL.createObjectURL(blob) };
      clipRef.current = next;
      setClip(next);
    };

    startedAtRef.current = Date.now();
    recorder.start(); // one blob on stop (Safari's mp4 fragments don't like being re-joined)
    setRecording(true);
    setElapsedMs(0);
    timerRef.current = setInterval(() => {
      const ms = Date.now() - startedAtRef.current;
      setElapsedMs(ms);
      if (ms >= maxSeconds * 1000) stop();
    }, 200);
  }, [supported, reset, releaseStream, stop, maxSeconds]);

  // Leaving the screen mid-recording: drop it and free the mic.
  useEffect(() => {
    return () => {
      discardRef.current = true;
      const r = recorderRef.current;
      if (r && r.state !== "inactive") r.stop();
      streamRef.current?.getTracks().forEach((t) => t.stop());
      if (timerRef.current) clearInterval(timerRef.current);
      if (clipRef.current) URL.revokeObjectURL(clipRef.current.previewUrl);
    };
  }, []);

  return { supported, recording, elapsedMs, clip, error, start, stop, cancel, reset };
}
