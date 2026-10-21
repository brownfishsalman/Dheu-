-- =====================================================================
--  Dheu — posts stay up for 3 months (was 30 days)
--  New posts get the longer default; posts that are still live are
--  extended to 3 months after they were shared. Posts that have already
--  expired stay expired.
--  Keep in sync with POST_LIFETIME_LABEL in src/lib/constants.ts.
--  Run AFTER 0017_notes.sql
-- =====================================================================

alter table public.posts
  alter column expires_at set default (now() + interval '3 months');

update public.posts
set expires_at = created_at + interval '3 months'
where expires_at > now();
