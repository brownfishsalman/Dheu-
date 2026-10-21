-- =====================================================================
--  Dheu — notes
--  A short line of text (up to 60 characters) shown above your avatar at
--  the top of the Messages inbox for 24 hours. One note per member: a new
--  one replaces the old. Seen by mutual follows (people you follow who
--  follow you back), never across a block. Replies arrive as a DM that
--  quotes the note.
--  Run AFTER 0016_account_privacy.sql
-- =====================================================================

create table public.notes (
  author_id   uuid primary key references public.profiles (id) on delete cascade,
  body        text not null check (char_length(body) between 1 and 60),
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null default (now() + interval '24 hours')
);
create index notes_expires_idx on public.notes (expires_at);

-- A DM that replies to a note keeps a copy of the note's text, since the
-- note itself is gone a day later.
alter table public.messages
  add column note_text text check (note_text is null or char_length(note_text) <= 60);
grant insert (note_text) on public.messages to authenticated;

-- ---------------------------------------------------------------------
-- Writing goes through set_note (the database stamps the 24 hours)
-- ---------------------------------------------------------------------

create or replace function public.set_note(p_body text)
returns void
language plpgsql security definer
set search_path = public
as $$
declare
  me     uuid := auth.uid();
  v_body text := btrim(p_body);
begin
  if me is null then raise exception 'not authenticated'; end if;
  if v_body is null or char_length(v_body) not between 1 and 60 then raise exception 'NOTE_LENGTH'; end if;
  insert into public.notes (author_id, body) values (me, v_body)
  on conflict (author_id) do update
    set body = excluded.body, created_at = now(), expires_at = now() + interval '24 hours';
end;
$$;

-- ---------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------

alter table public.notes enable row level security;

create policy "author and mutual follows read live notes" on public.notes
  for select to authenticated using (
    expires_at > now() and (
      author_id = (select auth.uid())
      or (
        exists (select 1 from public.follows f
                 where f.follower_id = (select auth.uid()) and f.following_id = author_id)
        and exists (select 1 from public.follows f
                     where f.follower_id = author_id and f.following_id = (select auth.uid()))
        and not public.blocked_either((select auth.uid()), author_id)
      )
    )
  );
create policy "authors delete own note" on public.notes
  for delete to authenticated using (author_id = (select auth.uid()));

-- members create and replace notes only through set_note
revoke insert, update on public.notes from authenticated;

notify pgrst, 'reload schema';
