-- =====================================================================
--  Dheu — public or private accounts
--  Until now every account was private. Members now choose:
--    public   anyone on Dheu sees their posts, stories, highlights and
--             follower lists, and following them is instant
--    private  as before: follow requests, content for approved followers
--  New members pick at sign-up. Existing members stay private until they
--  choose (privacy_chosen_at is null), and the app asks them to.
--  Blocking still overrides everything.
--  Run AFTER 0015_rls_initplan.sql
-- =====================================================================

alter table public.profiles
  add column is_private        boolean not null default true,
  add column privacy_chosen_at timestamptz;

-- Sign-up passes the choice in the auth user's metadata ("is_private").
create or replace function public.handle_new_user()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  insert into public.profiles (id, username, full_name, invite_code_id, is_admin, is_private, privacy_chosen_at)
  values (
    new.id,
    new.raw_user_meta_data ->> 'username',
    coalesce(new.raw_user_meta_data ->> 'full_name', ''),
    nullif(new.raw_user_meta_data ->> 'invite_code_id', '')::uuid,
    not exists (select 1 from public.profiles),
    coalesce((new.raw_user_meta_data ->> 'is_private')::boolean, true),
    case when new.raw_user_meta_data ? 'is_private' then now() end
  );
  return new;
end;
$$;

-- Content is visible to: yourself, the admin, anyone if the author is
-- public, approved followers if private. Never across a block.
create or replace function public.can_view_content_of(p_author uuid)
returns boolean
language sql stable security definer
set search_path = public
as $$
  select (
    p_author = auth.uid()
    or public.is_admin()
    or not coalesce((select p.is_private from public.profiles p where p.id = p_author), true)
    or exists (select 1 from public.follows where follower_id = auth.uid() and following_id = p_author)
  ) and not public.blocked_either(auth.uid(), p_author);
$$;

-- Follow someone: instantly if their account is public, by request if it
-- is private. Returns 'following' or 'requested'.
create or replace function public.follow_user(p_target uuid)
returns text
language plpgsql security definer
set search_path = public
as $$
declare
  me        uuid := auth.uid();
  v_private boolean;
begin
  if me is null then raise exception 'not authenticated'; end if;
  if p_target = me then raise exception 'cannot follow yourself'; end if;
  select is_private into v_private from public.profiles where id = p_target;
  if not found then raise exception 'user not found'; end if;
  if public.blocked_either(me, p_target) then raise exception 'blocked'; end if;
  if exists (select 1 from public.follows where follower_id = me and following_id = p_target) then
    return 'following';
  end if;

  if v_private then
    insert into public.follow_requests (requester_id, target_id) values (me, p_target)
    on conflict do nothing;
    return 'requested';
  end if;

  insert into public.follows (follower_id, following_id) values (me, p_target)
  on conflict do nothing;
  delete from public.follow_requests where requester_id = me and target_id = p_target;
  return 'following';
end;
$$;

-- Switch my account between public and private. Going public approves
-- everyone who was waiting: a public account has nothing left to approve.
-- Going private keeps existing followers.
create or replace function public.set_account_privacy(p_private boolean)
returns void
language plpgsql security definer
set search_path = public
as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not authenticated'; end if;
  if p_private is null then raise exception 'choose public or private'; end if;

  update public.profiles set is_private = p_private, privacy_chosen_at = now() where id = me;

  if not p_private then
    insert into public.follows (follower_id, following_id)
    select r.requester_id, me from public.follow_requests r where r.target_id = me
    on conflict do nothing;
    insert into public.notifications (user_id, actor_id, type)
    select r.requester_id, me, 'follow_accepted' from public.follow_requests r where r.target_id = me;
    delete from public.follow_requests where target_id = me;
  end if;
end;
$$;

notify pgrst, 'reload schema';
