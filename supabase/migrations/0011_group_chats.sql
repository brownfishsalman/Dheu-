-- =====================================================================
--  Dheu — group chats
--  A conversation is now either a 1-to-1 DM (user_a / user_b, unchanged)
--  or a group (is_group, title). Membership for both lives in
--  conversation_members, which becomes the single source of truth for
--  "who can read / send here".
--  Run AFTER 0010_voice_messages.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------

alter table public.conversations
  add column is_group   boolean not null default false,
  add column title      text check (title is null or char_length(title) between 1 and 60),
  add column created_by uuid references public.profiles (id) on delete set null,
  alter column user_a drop not null,
  alter column user_b drop not null;

-- DMs have both users and no title; groups have a title and neither user.
alter table public.conversations add constraint conversations_shape_check
  check (
    (is_group and user_a is null and user_b is null and title is not null)
    or (not is_group and user_a is not null and user_b is not null)
  );

create table public.conversation_members (
  conversation_id  uuid not null references public.conversations (id) on delete cascade,
  user_id          uuid not null references public.profiles (id) on delete cascade,
  added_by         uuid references public.profiles (id) on delete set null,
  joined_at        timestamptz not null default now(),
  primary key (conversation_id, user_id)
);
create index conversation_members_user_idx on public.conversation_members (user_id);

-- Existing DMs: two member rows each.
insert into public.conversation_members (conversation_id, user_id)
select id, user_a from public.conversations where user_a is not null
union all
select id, user_b from public.conversations where user_b is not null
on conflict do nothing;

-- New DMs (get_or_create_conversation) keep their membership in sync.
create or replace function public.add_dm_members()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  if not new.is_group then
    insert into public.conversation_members (conversation_id, user_id)
    values (new.id, new.user_a), (new.id, new.user_b)
    on conflict do nothing;
  end if;
  return new;
end;
$$;
create trigger on_conversation_insert_members
  after insert on public.conversations
  for each row execute function public.add_dm_members();

-- ---------------------------------------------------------------------
-- Helper functions
-- ---------------------------------------------------------------------

-- Membership is the single source of truth (was user_a / user_b).
create or replace function public.is_conversation_member(p_conversation_id uuid)
returns boolean
language sql stable security definer
set search_path = public
as $$
  select exists (
    select 1 from public.conversation_members m
    where m.conversation_id = p_conversation_id and m.user_id = auth.uid()
  );
$$;

-- Can the caller read this message? A member, and - in a group - not on
-- either side of a block with the sender. (In a DM a block stops sending
-- instead; history stays readable, as before.)
create or replace function public.can_read_message(p_conversation_id uuid, p_sender_id uuid)
returns boolean
language sql stable security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.conversations c
    join public.conversation_members m on m.conversation_id = c.id and m.user_id = auth.uid()
    where c.id = p_conversation_id
      and (not c.is_group or p_sender_id = auth.uid() or not public.blocked_either(auth.uid(), p_sender_id))
  );
$$;

-- Who runs a group: its creator while still a member, otherwise the
-- longest-standing member.
create or replace function public.group_owner(p_conversation_id uuid)
returns uuid
language sql stable security definer
set search_path = public
as $$
  select coalesce(
    (select c.created_by from public.conversations c
      where c.id = p_conversation_id and c.is_group
        and exists (select 1 from public.conversation_members m where m.conversation_id = c.id and m.user_id = c.created_by)),
    (select m.user_id from public.conversation_members m
      join public.conversations c on c.id = m.conversation_id and c.is_group
      where m.conversation_id = p_conversation_id
      order by m.joined_at, m.user_id limit 1)
  );
$$;

-- Unread message count per conversation (DMs and groups) for the caller.
create or replace function public.unread_counts()
returns table (conversation_id uuid, unread bigint)
language sql stable security invoker
set search_path = public
as $$
  select c.id, count(m.id)
  from public.conversations c
  join public.conversation_members cm
    on cm.conversation_id = c.id and cm.user_id = auth.uid()
  left join public.conversation_reads r
    on r.conversation_id = c.id and r.user_id = auth.uid()
  left join public.messages m
    on m.conversation_id = c.id
   and m.sender_id <> auth.uid()
   and m.created_at > coalesce(r.last_read_at, 'epoch'::timestamptz)
  group by c.id;
$$;

-- ---------------------------------------------------------------------
-- Group management (all checks inside; callers only get what these allow)
-- ---------------------------------------------------------------------

-- Start a group with at least two other people. Returns its id.
create or replace function public.create_group_conversation(p_title text, p_member_ids uuid[])
returns uuid
language plpgsql security definer
set search_path = public
as $$
declare
  me      uuid := auth.uid();
  v_title text := trim(p_title);
  cid     uuid;
  others  uuid[];
begin
  if me is null then raise exception 'not authenticated'; end if;
  if v_title is null or char_length(v_title) not between 1 and 60 then raise exception 'GROUP_TITLE'; end if;

  -- real members, not me, not suspended, not across a block
  select array_agg(distinct p.id) into others
  from public.profiles p
  where p.id = any (p_member_ids)
    and p.id <> me
    and p.banned_at is null
    and not public.blocked_either(me, p.id);
  if others is null or array_length(others, 1) < 2 then raise exception 'GROUP_TOO_SMALL'; end if;
  if array_length(others, 1) > 49 then raise exception 'GROUP_TOO_BIG'; end if;

  insert into public.conversations (is_group, title, created_by, last_message_at)
  values (true, v_title, me, now())
  returning id into cid;

  insert into public.conversation_members (conversation_id, user_id, added_by)
  select cid, u, me from unnest(others || me) as u;

  -- nobody starts with unread history
  insert into public.conversation_reads (conversation_id, user_id, last_read_at)
  select cid, u, now() from unnest(others || me) as u;

  return cid;
end;
$$;

-- Any member can add people. Silently skips people already in, suspended,
-- or across a block.
create or replace function public.add_group_members(p_conversation_id uuid, p_member_ids uuid[])
returns void
language plpgsql security definer
set search_path = public
as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not authenticated'; end if;
  if not exists (
    select 1 from public.conversations c
    join public.conversation_members m on m.conversation_id = c.id and m.user_id = me
    where c.id = p_conversation_id and c.is_group
  ) then raise exception 'not a member'; end if;

  with candidates as (
    select p.id
    from public.profiles p
    where p.id = any (p_member_ids)
      and p.id <> me
      and p.banned_at is null
      and not public.blocked_either(me, p.id)
      and not exists (select 1 from public.conversation_members m where m.conversation_id = p_conversation_id and m.user_id = p.id)
  ), added as (
    insert into public.conversation_members (conversation_id, user_id, added_by)
    select p_conversation_id, id, me from candidates
    returning user_id
  )
  insert into public.conversation_reads (conversation_id, user_id, last_read_at)
  select p_conversation_id, user_id, now() from added
  on conflict (conversation_id, user_id) do update set last_read_at = now();

  if (select count(*) from public.conversation_members where conversation_id = p_conversation_id) > 50 then
    raise exception 'GROUP_TOO_BIG';
  end if;
end;
$$;

-- Leave a group. If the creator leaves, the longest-standing member takes over.
create or replace function public.leave_group(p_conversation_id uuid)
returns void
language plpgsql security definer
set search_path = public
as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not authenticated'; end if;
  if not exists (select 1 from public.conversations where id = p_conversation_id and is_group) then
    raise exception 'not a group';
  end if;
  delete from public.conversation_members where conversation_id = p_conversation_id and user_id = me;
  delete from public.conversation_reads   where conversation_id = p_conversation_id and user_id = me;
  update public.conversations
  set created_by = (select user_id from public.conversation_members
                    where conversation_id = p_conversation_id order by joined_at, user_id limit 1)
  where id = p_conversation_id and created_by = me;
end;
$$;

-- Only the owner removes others (leaving is leave_group).
create or replace function public.remove_group_member(p_conversation_id uuid, p_user_id uuid)
returns void
language plpgsql security definer
set search_path = public
as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not authenticated'; end if;
  if p_user_id = me then raise exception 'use leave_group'; end if;
  if public.group_owner(p_conversation_id) is distinct from me then raise exception 'NOT_OWNER'; end if;
  delete from public.conversation_members where conversation_id = p_conversation_id and user_id = p_user_id;
  delete from public.conversation_reads   where conversation_id = p_conversation_id and user_id = p_user_id;
end;
$$;

-- Any member can rename.
create or replace function public.rename_group(p_conversation_id uuid, p_title text)
returns void
language plpgsql security definer
set search_path = public
as $$
declare
  me      uuid := auth.uid();
  v_title text := trim(p_title);
begin
  if me is null then raise exception 'not authenticated'; end if;
  if v_title is null or char_length(v_title) not between 1 and 60 then raise exception 'GROUP_TITLE'; end if;
  if not exists (
    select 1 from public.conversations c
    join public.conversation_members m on m.conversation_id = c.id and m.user_id = me
    where c.id = p_conversation_id and c.is_group
  ) then raise exception 'not a member'; end if;
  update public.conversations set title = v_title where id = p_conversation_id;
end;
$$;

-- Nightly: groups everyone has left, once their photos / voice notes are
-- gone (the cleanup job removes those files first). Server-only.
create or replace function public.delete_empty_groups()
returns integer
language sql security definer
set search_path = public
as $$
  with gone as (
    delete from public.conversations c
    where c.is_group
      and not exists (select 1 from public.conversation_members m where m.conversation_id = c.id)
      and not exists (select 1 from public.messages x where x.conversation_id = c.id
                        and (x.image_path is not null or x.audio_path is not null))
    returning c.id
  )
  select count(*)::integer from gone;
$$;
revoke execute on function public.delete_empty_groups() from public, anon, authenticated;

-- ---------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------

alter table public.conversation_members enable row level security;

-- Members see who else is in their conversations. Rows are only ever
-- written by the functions above.
create policy "members see who is in their conversations" on public.conversation_members
  for select to authenticated using (public.is_conversation_member(conversation_id));

drop policy "members read own conversations" on public.conversations;
create policy "members read own conversations" on public.conversations
  for select to authenticated using (public.is_conversation_member(id));

drop policy "members read own messages" on public.messages;
create policy "members read own messages" on public.messages
  for select to authenticated using (public.can_read_message(conversation_id, sender_id));
