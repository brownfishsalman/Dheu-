-- =====================================================================
--  Dheu — cheaper row-level security checks (optional)
--  Postgres re-evaluates a bare auth.uid() / public.is_admin() for every
--  row a policy looks at. Wrapped as (select auth.uid()) it runs once per
--  query instead (Supabase's "auth_rls_initplan" advice). Each policy
--  below keeps its name, command, roles and meaning: the only change is
--  that wrapping. Policies that don't call either function are untouched,
--  as are the storage policies.
--  The app doesn't depend on this file; it can run any time after 0014.
--  Run AFTER 0014_rpc_reads.sql
-- =====================================================================

begin;

-- invite_codes
alter policy "admin manages invite codes" on public.invite_codes
  using ((select public.is_admin())) with check ((select public.is_admin()));
alter policy "members read own invite codes" on public.invite_codes
  using (created_by = (select auth.uid()));

-- profiles
alter policy "members can read profiles" on public.profiles
  using (id = (select auth.uid()) or not public.has_blocked_me(id));
alter policy "users edit own profile" on public.profiles
  using (id = (select auth.uid())) with check (id = (select auth.uid()));
alter policy "admin edits any profile" on public.profiles
  using ((select public.is_admin())) with check ((select public.is_admin()));

-- follows
alter policy "either side removes a follow" on public.follows
  using (follower_id = (select auth.uid()) or following_id = (select auth.uid()));

-- posts
alter policy "users create posts" on public.posts
  with check (author_id = (select auth.uid()));
alter policy "users edit own posts" on public.posts
  using (author_id = (select auth.uid())) with check (author_id = (select auth.uid()));
alter policy "users or admin delete posts" on public.posts
  using (author_id = (select auth.uid()) or (select public.is_admin()));

-- post_images
alter policy "authors add images" on public.post_images
  with check (
    exists (select 1 from public.posts p where p.id = post_id and p.author_id = (select auth.uid()))
  );
alter policy "authors or admin delete images" on public.post_images
  using (
    (select public.is_admin()) or
    exists (select 1 from public.posts p where p.id = post_id and p.author_id = (select auth.uid()))
  );

-- post_likes
alter policy "followers read likes" on public.post_likes
  using (
    exists (select 1 from public.posts p where p.id = post_id)
    and not public.blocked_either((select auth.uid()), user_id)
  );
alter policy "users like visible posts" on public.post_likes
  with check (
    user_id = (select auth.uid()) and exists (select 1 from public.posts p where p.id = post_id)
  );
alter policy "users unlike" on public.post_likes
  using (user_id = (select auth.uid()));

-- comments
alter policy "followers read comments" on public.comments
  using (
    exists (select 1 from public.posts p where p.id = post_id)
    and not public.blocked_either((select auth.uid()), author_id)
  );
alter policy "users comment on visible posts" on public.comments
  with check (
    author_id = (select auth.uid()) and exists (select 1 from public.posts p where p.id = post_id)
  );
alter policy "author, post owner or admin delete comments" on public.comments
  using (
    author_id = (select auth.uid()) or (select public.is_admin()) or
    exists (select 1 from public.posts p where p.id = post_id and p.author_id = (select auth.uid()))
  );

-- stories
alter policy "users post stories" on public.stories
  with check (author_id = (select auth.uid()));
alter policy "users or admin delete stories" on public.stories
  using (author_id = (select auth.uid()) or (select public.is_admin()));

-- story_views
alter policy "author or viewer reads views" on public.story_views
  using (
    viewer_id = (select auth.uid()) or
    exists (select 1 from public.stories s where s.id = story_id and s.author_id = (select auth.uid()))
  );
alter policy "users record own views" on public.story_views
  with check (viewer_id = (select auth.uid()));

-- story_reactions
alter policy "author or reactor reads reactions" on public.story_reactions
  using (
    user_id = (select auth.uid()) or
    exists (select 1 from public.stories s where s.id = story_id and s.author_id = (select auth.uid()))
  );
alter policy "users react" on public.story_reactions
  with check (user_id = (select auth.uid()));
alter policy "users change reaction" on public.story_reactions
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
alter policy "users remove reaction" on public.story_reactions
  using (user_id = (select auth.uid()));

-- story_highlights
alter policy "users manage own highlights" on public.story_highlights
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
alter policy "admin deletes highlights" on public.story_highlights
  using ((select public.is_admin()));

-- highlight_items
alter policy "owners add own stories to own highlights" on public.highlight_items
  with check (
    exists (select 1 from public.story_highlights h where h.id = highlight_id and h.owner_id = (select auth.uid())) and
    exists (select 1 from public.stories s where s.id = story_id and s.author_id = (select auth.uid()))
  );
alter policy "owners or admin remove highlight items" on public.highlight_items
  using (
    (select public.is_admin()) or
    exists (select 1 from public.story_highlights h where h.id = highlight_id and h.owner_id = (select auth.uid()))
  );

-- messages
alter policy "members send messages" on public.messages
  with check (
    sender_id = (select auth.uid())
    and public.is_conversation_member(conversation_id)
    and not public.conversation_blocked(conversation_id)
  );
alter policy "senders or admin delete messages" on public.messages
  using (sender_id = (select auth.uid()) or (select public.is_admin()));

-- conversation_reads
alter policy "users manage own read markers" on public.conversation_reads
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- notifications
alter policy "users read own notifications" on public.notifications
  using (user_id = (select auth.uid()));
alter policy "users mark own notifications read" on public.notifications
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
alter policy "users delete own notifications" on public.notifications
  using (user_id = (select auth.uid()));

-- blocks
alter policy "users see own blocks" on public.blocks
  using (blocker_id = (select auth.uid()));
alter policy "users block" on public.blocks
  with check (blocker_id = (select auth.uid()));
alter policy "users unblock" on public.blocks
  using (blocker_id = (select auth.uid()));

-- follow_requests
alter policy "parties see follow requests" on public.follow_requests
  using (requester_id = (select auth.uid()) or target_id = (select auth.uid()));
alter policy "users send follow requests" on public.follow_requests
  with check (
    requester_id = (select auth.uid())
    and not public.blocked_either((select auth.uid()), target_id)
    and not exists (select 1 from public.follows where follower_id = (select auth.uid()) and following_id = target_id)
  );
alter policy "requester cancels or target declines" on public.follow_requests
  using (requester_id = (select auth.uid()) or target_id = (select auth.uid()));

-- announcements
alter policy "admin posts announcements" on public.announcements
  with check ((select public.is_admin()) and author_id = (select auth.uid()));
alter policy "admin edits announcements" on public.announcements
  using ((select public.is_admin())) with check ((select public.is_admin()));
alter policy "admin deletes announcements" on public.announcements
  using ((select public.is_admin()));

-- announcement_reads
alter policy "users manage own announcement read marker" on public.announcement_reads
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- suggested_people
alter policy "admin manages suggestions" on public.suggested_people
  using ((select public.is_admin())) with check ((select public.is_admin()));

-- push_subscriptions
alter policy "users manage own push subscriptions" on public.push_subscriptions
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

commit;
