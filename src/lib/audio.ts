// Browser-only voice message helpers: which format MediaRecorder should
// produce here, and how to name / label the result.

// Preferred first. Chrome, Firefox and Safari 18.4+ record webm/opus; older
// Safari records mp4/aac. Every browser we support plays both.
const CANDIDATES = ["audio/webm;codecs=opus", "audio/mp4", "audio/ogg;codecs=opus", "audio/webm"];

export function canRecordAudio(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof MediaRecorder !== "undefined" &&
    Boolean(navigator.mediaDevices?.getUserMedia)
  );
}

// The first mime type this browser's MediaRecorder supports ("" = let it choose).
export function pickRecordingMime(): string {
  if (typeof MediaRecorder === "undefined") return "";
  return CANDIDATES.find((t) => MediaRecorder.isTypeSupported(t)) ?? "";
}

// "audio/webm;codecs=opus" → "audio/webm" (what Storage validates against).
export function baseMime(mime: string): string {
  const base = mime.split(";")[0].trim().toLowerCase();
  return base || "audio/webm";
}

function extensionFor(mime: string): string {
  switch (baseMime(mime)) {
    case "audio/mp4":
      return "m4a";
    case "audio/ogg":
      return "ogg";
    case "audio/mpeg":
      return "mp3";
    default:
      return "webm";
  }
}

export function newAudioPath(userId: string, mime: string) {
  return `${userId}/${crypto.randomUUID()}.${extensionFor(mime)}`;
}

// 65_000 → "1:05"
export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}
