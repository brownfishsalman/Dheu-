// Shared between server and client code (no server-only imports here).

// What a system line (messages.kind = 'system') records; written only by the
// group functions in supabase/migrations/0012_group_system_messages.sql.
export type SystemEvent = {
  type: "created" | "added" | "removed" | "left" | "renamed";
  actor: string;
  targets?: string[];
  title?: string;
};

// Every user id a message names: its sender, plus the people a system line
// mentions ("Salman added Rafi, Tania"). Used to look names up.
export function eventPeople(m: { sender_id: string; event: SystemEvent | null }): string[] {
  return [m.sender_id, ...(m.event?.targets ?? [])];
}
