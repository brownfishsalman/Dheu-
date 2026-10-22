// @mentions in captions, comments and group messages. Works on server and client.
// The database reads mentions with the same rule (mentioned_profiles() in
// 0020_mentions_app_push.sql), and so does the Android app (data/Mentions.kt):
// an @ at the start or after a character that can't be part of a username,
// then 3–30 of [a-z0-9_.]. A trailing full stop ends the sentence, not the name.

const MENTION = /(^|[^a-z0-9_.@])@([a-z0-9_.]{3,30})/gi;

export type MentionPart = { text: string } | { username: string; text: string };

// "hi @rafi." → [{text: "hi "}, {username: "rafi", text: "@rafi"}, {text: "."}]
export function splitMentions(text: string): MentionPart[] {
  const parts: MentionPart[] = [];
  let last = 0;
  for (const m of text.matchAll(MENTION)) {
    const name = m[2].replace(/\.+$/, "");
    if (name.length < 3) continue;
    const at = m.index + m[1].length;
    if (at > last) parts.push({ text: text.slice(last, at) });
    parts.push({ username: name.toLowerCase(), text: `@${name}` });
    last = at + 1 + name.length;
  }
  if (last < text.length) parts.push({ text: text.slice(last) });
  return parts;
}

// The @word being typed just before the caret, if any: where its @ is and what follows it.
export function mentionQueryAt(value: string, caret: number): { start: number; query: string } | null {
  const before = value.slice(0, caret);
  const m = /(^|[^a-z0-9_.@])@([a-z0-9_.]{0,30})$/i.exec(before);
  if (!m) return null;
  return { start: caret - m[2].length - 1, query: m[2].toLowerCase() };
}

// Replace the @word being typed with the chosen username and a space.
export function insertMention(value: string, start: number, caret: number, username: string) {
  const inserted = `@${username} `;
  const rest = value.slice(caret).replace(/^[a-z0-9_.]*/i, "").replace(/^ /, "");
  return { value: value.slice(0, start) + inserted + rest, caret: start + inserted.length };
}
