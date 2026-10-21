-- =====================================================================
--  Dheu — delete a chat, unsend a message
--  Delete chat (Instagram-style): removes the conversation from MY inbox
--    and hides its history from me. Other members keep theirs. A new
--    message brings the chat back, showing only what came after.
--    The marker lives in conversation_reads, which only its owner can
--    see, so nobody learns that someone else deleted the chat.
--  Unsend: the sender removes their own message for everyone.
--  Run AFTER 0018_posts_three_months.sql
-- =====================================================================

alter table public.conversation_reads add column cleared_at timestamptz;

-- ---------------------------------------------------------------------
-- Delete chat
-- ---------------------------------------------------------------------

-- Can the caller read this message? A member who hasn't deleted the chat
-- since it was sent, and - in a group - not on either side of a block
-- with the sender.
create or replace function public.can_read_message(p_conversation_id uuid, p_sender_id uuid, p_created_at timestamptz)
returns boolean
language sql stable security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.conversations c
    join public.conversation_members m on m.conversation_id = c.id and m.user_id = auth.uid()
    left join public.conversation_reads r on r.conversation_id = c.id and r.user_id = auth.uid()
    where c.id = p_conversation_id
      and (r.cleared_at is null or p_created_at > r.cleared_at)
      and (not c.is_group or p_sender_id = auth.uid() or not public.blocked_either(auth.uid(), p_sender_id))
  );
$$;

drop policy "members read own messages" on public.messages;
create policy "members read own messages" on public.messages
  for select to authenticated using (public.can_read_message(conversation_id, sender_id, created_at));

drop function public.can_read_message(uuid, uuid);

create or replace function public.delete_chat_for_me(p_conversation_id uuid)
returns void
language plpgsql security definer
set search_path = public
as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not authenticated'; end if;
  if not public.is_conversation_member(p_conversation_id) then raise exception 'not a member'; end if;
  insert into public.conversation_reads (conversation_id, user_id, last_read_at, cleared_at)
  values (p_conversation_id, me, now(), now())
  on conflict (conversation_id, user_id) do update set last_read_at = now(), cleared_at = now();
end;
$$;

-- ---------------------------------------------------------------------
-- Unsend
-- ---------------------------------------------------------------------

-- Senders may delete their own ordinary messages (not system lines);
-- the admin may delete any.
drop policy "senders or admin delete messages" on public.messages;
create policy "senders unsend own messages, admin deletes any" on public.messages
  for delete to authenticated using (
    (sender_id = (select auth.uid()) and kind = 'text') or (select public.is_admin())
  );

-- Keep the inbox order right when the newest message disappears.
create or replace function public.retouch_conversation()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  update public.conversations
  set last_message_at = (select max(m.created_at) from public.messages m where m.conversation_id = old.conversation_id)
  where id = old.conversation_id;
  return old;
end;
$$;

create trigger on_message_delete
  after delete on public.messages
  for each row execute function public.retouch_conversation();

notify pgrst, 'reload schema';
