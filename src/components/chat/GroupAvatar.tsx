import { Users } from "lucide-react";
import { Avatar } from "@/components/ui/Avatar";
import type { ChatPeer } from "@/lib/data/chat";

// Two overlapping member avatars (or a plain icon while a group has none
// I can see), sized like a single Avatar so lists line up.
export function GroupAvatar({ members, size = 52, className = "" }: { members: ChatPeer[]; size?: number; className?: string }) {
  const [a, b] = members;
  if (!a) {
    return (
      <span
        style={{ width: size, height: size }}
        className={`flex shrink-0 items-center justify-center rounded-full bg-brand-soft text-brand ${className}`}
        aria-hidden
      >
        <Users style={{ width: size * 0.5, height: size * 0.5 }} />
      </span>
    );
  }
  if (!b) return <Avatar path={a.avatar_path} name={a.full_name} size={size} className={className} />;

  const small = Math.round(size * 0.68);
  return (
    <span style={{ width: size, height: size }} className={`relative block shrink-0 ${className}`} aria-hidden>
      <Avatar path={b.avatar_path} name={b.full_name} size={small} className="absolute top-0 right-0" />
      <Avatar path={a.avatar_path} name={a.full_name} size={small} className="absolute bottom-0 left-0 ring-2 ring-surface" />
    </span>
  );
}
