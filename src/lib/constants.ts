// Shared between server and client code (no server-only imports here).
export const MESSAGE_PAGE = 50;
export const FEED_PAGE_SIZE = 8;
export const MEMBER_INVITE_LIMIT = 5; // unused invite codes a member may hold at once
export const CHAT_PHOTO_DAYS = 7; // chat photos disappear after this (stamped by the DB — see 0010)
export const CHAT_VOICE_DAYS = 7; // voice messages disappear after this (stamped by the DB — see 0010)
export const VOICE_MAX_SECONDS = 120; // a voice message stops recording here
