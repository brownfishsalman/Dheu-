-- =====================================================================
--  Dheu — system lines in group chats
--  "Salman created the group", "Salman added Rafi, Tania", "Tania left",
--  "Salman removed Rafi", "Salman renamed the group to "Trip"".
--  Stored as messages with kind = 'system' and a small JSON event, written
--  only by the group functions; never counted as unread.
--  Run AFTER 0011_group_chats.sql
-- =====================================================================

alter table public.messages
  add column kind  text  not null default 'text' check (kind in ('text', 'system')),
  add column event jsonb check ((kind = 'system') = (event is not null));

-- Members may write ordinary messages only; kind / event / timestamps come
-- from defaults and the functions below.
revoke insert on public.messages from authenticated;
grant  insert (conversation_id, sender_id, body, story_id, image_path, audio_path, audio_duration_ms, reply_to_id)
  on public.messages to authenticated;

-- System lines don't make a chat "unread".
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
   and m.kind <> 'system'
   and m.created_at > coalesce(r.last_read_at, 'epoch'::timestamptz)
  group by c.id;
$$;

-- Writes one system line. body is a plain-English fallback (inbox preview,
-- push); the app renders from event so it can say "You".
create or replace function public.post_system_message(
  p_conversation_id uuid, p_actor uuid, p_type text, p_targets uuid[] default null, p_title text default null
)
returns void
language plpgsql security definer
set search_path = public
as $$
declare
  actor_name   text;
  target_names text;
  sentence     text;
begin
  select full_name into actor_name from public.profiles where id = p_actor;
  if p_targets is not null then
    select string_agg(full_name, ', ' order by full_name) into target_names
    from public.profiles where id = any (p_targets);
  end if;
  sentence := case p_type
    when 'created' then actor_name || ' created the group'
    when 'added'   then actor_name || ' added ' || coalesce(target_names, 'someone')
    when 'removed' then actor_name || ' removed ' || coalesce(target_names, 'someone')
    when 'left'    then actor_name || ' left'
    when 'renamed' then actor_name || ' renamed the group to "' || coalesce(p_title, '') || '"'
    else actor_name || ' ' || p_type
  end;
  insert into public.messages (conversation_id, sender_id, kind, body, event)
  values (
    p_conversation_id, p_actor, 'system', left(sentence, 2000),
    jsonb_strip_nulls(jsonb_build_object('type', p_type, 'actor', p_actor, 'targets', to_jsonb(p_targets), 'title', p_title))
  );
end;
$$;
revoke execute on function public.post_system_message(uuid, uuid, text, uuid[], text) from public, anon, authenticated;

-- ---------------------------------------------------------------------
-- Group functions, now announcing what they did
-- ---------------------------------------------------------------------

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

  insert into public.conversation_reads (conversation_id, user_id, last_read_at)
  select cid, u, now() from unnest(others || me) as u;

  perform public.post_system_message(cid, me, 'created');
  perform public.post_system_message(cid, me, 'added', others);
  return cid;
end;
$$;

create or replace function public.add_group_members(p_conversation_id uuid, p_member_ids uuid[])
returns void
language plpgsql security definer
set search_path = public
as $$
declare
  me      uuid := auth.uid();
  v_added uuid[];
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
  ), marked as (
    insert into public.conversation_reads (conversation_id, user_id, last_read_at)
    select p_conversation_id, user_id, now() from added
    on conflict (conversation_id, user_id) do update set last_read_at = now()
    returning user_id
  )
  select array_agg(user_id) into v_added from marked;

  if (select count(*) from public.conversation_members where conversation_id = p_conversation_id) > 50 then
    raise exception 'GROUP_TOO_BIG';
  end if;

  if v_added is not null then
    perform public.post_system_message(p_conversation_id, me, 'added', v_added);
  end if;
end;
$$;

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
  if not found then return; end if;
  delete from public.conversation_reads where conversation_id = p_conversation_id and user_id = me;
  update public.conversations
  set created_by = (select user_id from public.conversation_members
                    where conversation_id = p_conversation_id order by joined_at, user_id limit 1)
  where id = p_conversation_id and created_by = me;
  perform public.post_system_message(p_conversation_id, me, 'left');
end;
$$;

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
  if not found then return; end if;
  delete from public.conversation_reads where conversation_id = p_conversation_id and user_id = p_user_id;
  perform public.post_system_message(p_conversation_id, me, 'removed', array[p_user_id]);
end;
$$;

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
  update public.conversations set title = v_title where id = p_conversation_id and title is distinct from v_title;
  if found then
    perform public.post_system_message(p_conversation_id, me, 'renamed', null, v_title);
  end if;
end;
$$;
