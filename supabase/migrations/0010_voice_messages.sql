-- =====================================================================
--  Dheu — voice messages in chat
--  Recorded in the browser (MediaRecorder), stored in the "chat" bucket,
--  gone after 7 days like chat photos.
--  Run AFTER 0009_push_failsafe.sql
-- =====================================================================

alter table public.messages
  add column audio_path        text,
  add column audio_duration_ms integer check (audio_duration_ms is null or audio_duration_ms between 1 and 600000),
  add column audio_expires_at  timestamptz;

-- A message needs text, a photo or a voice note. A media-only message keeps
-- its row after the file expires (shown as "Photo expired" / "Voice message expired").
alter table public.messages drop constraint messages_body_check;
alter table public.messages add constraint messages_body_check
  check (
    char_length(body) <= 2000
    and (
      char_length(body) > 0
      or image_path is not null or image_expires_at is not null
      or audio_path is not null or audio_expires_at is not null
    )
  );

create index messages_audio_expiry_idx on public.messages (audio_expires_at) where audio_path is not null;

-- Media expiry is stamped with the database clock on insert, so a client can
-- neither pick its own expiry nor drift with a wrong phone clock.
-- (Keep the interval in sync with CHAT_PHOTO_DAYS / CHAT_VOICE_DAYS in src/lib/constants.ts.)
create or replace function public.stamp_message_media_expiry()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.image_path is not null then new.image_expires_at := now() + interval '7 days'; end if;
  if new.audio_path is not null then new.audio_expires_at := now() + interval '7 days'; end if;
  return new;
end;
$$;

create trigger on_message_stamp_expiry
  before insert on public.messages
  for each row execute function public.stamp_message_media_expiry();

-- ---------------------------------------------------------------------
-- Storage: the chat bucket now accepts audio
-- (webm/opus from Chrome, Firefox, Safari 18.4+; mp4/aac from older Safari)
-- ---------------------------------------------------------------------

update storage.buckets
set allowed_mime_types = array[
  'image/jpeg', 'image/png', 'image/webp',
  'audio/webm', 'audio/mp4', 'audio/ogg', 'audio/mpeg'
]
where id = 'chat';
