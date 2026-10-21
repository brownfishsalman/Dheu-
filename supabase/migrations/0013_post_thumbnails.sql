-- =====================================================================
--  Dheu — post thumbnails
--  New posts also upload a small square JPEG (~480 px) next to each photo,
--  used by the profile grid and other small tiles so they don't download
--  the full 1080 px image. Nullable: older posts have none and fall back
--  to the full-size file.
--  Run AFTER 0012_group_system_messages.sql
-- =====================================================================

alter table public.post_images
  add column if not exists thumb_path text;
