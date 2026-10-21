// Helpers for Supabase Storage public URLs. Works on server and client.

export type Bucket = "avatars" | "posts" | "stories" | "chat";

export function publicUrl(bucket: Bucket, path: string | null | undefined): string | null {
  if (!path) return null;
  return `${process.env.NEXT_PUBLIC_SUPABASE_URL}/storage/v1/object/public/${bucket}/${path}`;
}

export function avatarUrl(path: string | null | undefined): string | null {
  return publicUrl("avatars", path);
}

// Every "posts" bucket file behind these images: the photo plus its thumbnail, if it has one.
export function postImageFiles(images: { path: string; thumb_path: string | null }[]): string[] {
  return images.flatMap((i) => (i.thumb_path ? [i.path, i.thumb_path] : [i.path]));
}
