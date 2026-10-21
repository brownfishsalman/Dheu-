-- =====================================================================
--  Dheu — one request per read
--  A few screens used to fire several PostgREST requests (or wait for one
--  before starting the next). These replace each with a single request:
--    badge_counts()         chat + activity badges        (was 3 requests)
--    profile_stats(uuid)    profile header numbers        (was 8 requests)
--    home_feed (view)       my posts + people I follow    (no longer waits
--                           for the following list first)
--  All of them run as the caller (security invoker), so RLS decides what
--  they return exactly as it did for the queries they replace.
--  Run AFTER 0013_post_thumbnails.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- Badges
-- ---------------------------------------------------------------------

-- Unread chat messages (DMs, groups, announcements) and unread
-- notifications for the caller: the sums the app used to add up itself.
create or replace function public.badge_counts()
returns table (messages integer, activity integer)
language sql stable security invoker
set search_path = public
as $$
  select
    (coalesce((select sum(u.unread) from public.unread_counts() u), 0)
      + public.unread_announcement_count())::integer,
    (select count(*) from public.notifications n
      where n.user_id = auth.uid() and n.read_at is null)::integer;
$$;

-- ---------------------------------------------------------------------
-- Profile header
-- ---------------------------------------------------------------------

-- Everything the profile header needs about one member, as the caller sees
-- it. The post count goes through posts RLS, so it is 0 for anyone who
-- may not see their posts; follow requests only show to the two parties.
create or replace function public.profile_stats(p_profile uuid)
returns table (
  posts        integer,
  followers    integer,
  following    integer,
  is_following boolean,
  follows_me   boolean,
  requested    boolean,
  requested_me boolean,
  block_status text
)
language sql stable security invoker
set search_path = public
as $$
  select
    (select count(*) from public.posts p
      where p.author_id = p_profile and p.expires_at > now())::integer,
    (select count(*) from public.follows f where f.following_id = p_profile)::integer,
    (select count(*) from public.follows f where f.follower_id = p_profile)::integer,
    exists (select 1 from public.follows f
             where f.follower_id = auth.uid() and f.following_id = p_profile),
    exists (select 1 from public.follows f
             where f.follower_id = p_profile and f.following_id = auth.uid()),
    exists (select 1 from public.follow_requests r
             where r.requester_id = auth.uid() and r.target_id = p_profile),
    exists (select 1 from public.follow_requests r
             where r.requester_id = p_profile and r.target_id = auth.uid()),
    public.block_status(p_profile);
$$;

-- ---------------------------------------------------------------------
-- Home feed
-- ---------------------------------------------------------------------

-- post_feed narrowed to my own posts plus those of people I follow. Keyed
-- off the session, so the app no longer has to fetch who I follow before
-- it can ask for the feed. RLS on posts (through post_feed) still applies.
-- Same columns as post_feed, no UNION: PostgREST traces them back to posts
-- and embeds the author and post_images through it like it does for
-- post_feed.
create or replace view public.home_feed
with (security_invoker = true) as
select
  f.id,
  f.author_id,
  f.caption,
  f.created_at,
  f.expires_at,
  f.like_count,
  f.comment_count,
  f.liked_by_me
from public.post_feed f
where f.author_id = (select auth.uid())
   or f.author_id in (
     select fo.following_id from public.follows fo
     where fo.follower_id = (select auth.uid())
   );

-- Let the API see the new function and view straight away.
notify pgrst, 'reload schema';
