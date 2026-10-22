-- =====================================================================
--  Dheu — @mentions, and push notifications for the Android app
--  1) Writing @username in a post caption, a comment or a group message
--     notifies that member (Activity + push) when they can see it:
--     captions and comments only reach people allowed to see the post,
--     group messages only reach members of the group. Never across a block.
--  2) push_devices: the Android app's Firebase Cloud Messaging tokens.
--     /api/push sends to these as well as to the website's Web Push
--     subscriptions.
--  Run AFTER 0019_delete_chat_unsend.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- Mentions
-- ---------------------------------------------------------------------

-- A mention in a group chat points at the message.
alter table public.notifications
  add column conversation_id uuid references public.conversations (id) on delete cascade,
  add column message_id      uuid references public.messages (id) on delete cascade;

alter table public.notifications drop constraint notifications_type_check;
alter table public.notifications add constraint notifications_type_check
  check (type in ('like', 'follow', 'comment', 'story_reaction', 'follow_request', 'follow_accepted', 'mention'));

-- One mention notification per person per caption, comment or message.
create unique index notifications_mention_uniq
  on public.notifications (user_id, coalesce(message_id, comment_id, post_id)) where type = 'mention';

-- The members a text mentions. Same rule as the website and the app
-- (src/lib/mentions.ts, data/Mentions.kt): @ at the start or after a
-- character that can't be part of a username, then 3-30 of [a-z0-9_.].
-- A full stop straight after the name ends the sentence, not the name.
create or replace function public.mentioned_profiles(p_text text)
returns setof uuid
language sql stable
set search_path = public
as $$
  select distinct p.id
  from regexp_matches(coalesce(p_text, ''), '(?:^|[^a-z0-9_.@])@([a-z0-9_.]{3,30})', 'gi') as m (groups)
  join public.profiles p on p.username in (lower(m.groups[1]), lower(rtrim(m.groups[1], '.')))
  where p.banned_at is null;
$$;

-- can_view_content_of() for someone other than the caller: the mention
-- triggers run while the writer is signed in, but ask on behalf of the
-- person mentioned.
create or replace function public.can_user_view_content_of(p_viewer uuid, p_author uuid)
returns boolean
language sql stable security definer
set search_path = public
as $$
  select (
    p_author = p_viewer
    or coalesce((select p.is_admin from public.profiles p where p.id = p_viewer), false)
    or not coalesce((select p.is_private from public.profiles p where p.id = p_author), true)
    or exists (select 1 from public.follows where follower_id = p_viewer and following_id = p_author)
  ) and not public.blocked_either(p_viewer, p_author);
$$;
revoke execute on function public.can_user_view_content_of(uuid, uuid) from public, anon, authenticated;

-- Caption mentions fire when the first photo lands, not with the post row:
-- both clients insert the post, then its photos, and a notification for a
-- post with no photos yet would open an empty page.
create or replace function public.notify_caption_mentions()
returns trigger
language plpgsql security definer
set search_path = public
as $$
declare
  v_author  uuid;
  v_caption text;
begin
  select author_id, caption into v_author, v_caption from public.posts where id = new.post_id;
  if v_author is null or v_caption = '' then return new; end if;
  insert into public.notifications (user_id, actor_id, type, post_id)
  select m.id, v_author, 'mention', new.post_id
  from public.mentioned_profiles(v_caption) as m (id)
  where m.id <> v_author
    and public.can_user_view_content_of(m.id, v_author)
  on conflict do nothing;
  return new;
end;
$$;

create trigger on_post_image_caption_mentions
  after insert on public.post_images
  for each row when (new.position = 0)
  execute function public.notify_caption_mentions();

-- The post's author already hears about every comment, so a comment that
-- names them stays a plain 'comment' notification.
create or replace function public.notify_comment_mentions()
returns trigger
language plpgsql security definer
set search_path = public
as $$
declare v_author uuid;
begin
  select author_id into v_author from public.posts where id = new.post_id;
  if v_author is null then return new; end if;
  insert into public.notifications (user_id, actor_id, type, post_id, comment_id)
  select m.id, new.author_id, 'mention', new.post_id, new.id
  from public.mentioned_profiles(new.body) as m (id)
  where m.id <> new.author_id
    and m.id <> v_author
    and public.can_user_view_content_of(m.id, v_author)
    and not public.blocked_either(m.id, new.author_id)
  on conflict do nothing;
  return new;
end;
$$;

create trigger on_comment_mentions
  after insert on public.comments
  for each row execute function public.notify_comment_mentions();

-- Group chats only: in a 1-to-1 chat the other person sees every message anyway.
-- Runs before push_on_message (triggers fire in name order), so /api/push
-- can leave mentioned members out of the ordinary message push.
create or replace function public.notify_message_mentions()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  if new.kind <> 'text' or new.body = '' then return new; end if;
  if not exists (select 1 from public.conversations c where c.id = new.conversation_id and c.is_group) then
    return new;
  end if;
  insert into public.notifications (user_id, actor_id, type, conversation_id, message_id)
  select m.id, new.sender_id, 'mention', new.conversation_id, new.id
  from public.mentioned_profiles(new.body) as m (id)
  join public.conversation_members cm on cm.conversation_id = new.conversation_id and cm.user_id = m.id
  where m.id <> new.sender_id
    and not public.blocked_either(m.id, new.sender_id)
  on conflict do nothing;
  return new;
end;
$$;

create trigger on_message_mentions
  after insert on public.messages
  for each row execute function public.notify_message_mentions();

-- ---------------------------------------------------------------------
-- Android push devices (Firebase Cloud Messaging)
-- ---------------------------------------------------------------------

create table public.push_devices (
  token       text primary key,
  user_id     uuid not null references public.profiles (id) on delete cascade,
  platform    text not null default 'android' check (platform in ('android')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index push_devices_user_idx on public.push_devices (user_id);

alter table public.push_devices enable row level security;
create policy "users read own push devices" on public.push_devices
  for select to authenticated using (user_id = (select auth.uid()));
create policy "users remove own push devices" on public.push_devices
  for delete to authenticated using (user_id = (select auth.uid()));

-- A phone keeps its token when one member signs out and another signs in,
-- so registering takes the token over instead of failing on the old row
-- (which the new member can't see).
create or replace function public.register_push_device(p_token text)
returns void
language plpgsql security definer
set search_path = public
as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not authenticated'; end if;
  if p_token is null or char_length(p_token) not between 20 and 4096 then raise exception 'invalid token'; end if;
  insert into public.push_devices (token, user_id) values (p_token, me)
  on conflict (token) do update set user_id = me, updated_at = now();
end;
$$;

notify pgrst, 'reload schema';
