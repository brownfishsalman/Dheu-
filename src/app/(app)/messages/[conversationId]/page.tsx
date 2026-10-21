import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Info } from "lucide-react";
import { requireProfile } from "@/lib/data/session";
import { getConversation, getMessages, getPeers } from "@/lib/data/chat";
import { eventPeople } from "@/lib/chat-events";
import { PageHeader } from "@/components/ui/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { GroupAvatar } from "@/components/chat/GroupAvatar";
import { ChatThread } from "@/components/chat/ChatThread";

export async function generateMetadata(props: PageProps<"/messages/[conversationId]">): Promise<Metadata> {
  const { conversationId } = await props.params;
  const me = await requireProfile();
  const conv = await getConversation(conversationId, me.id);
  return { title: conv ? conv.title : "Messages" };
}

export default async function ConversationPage(props: PageProps<"/messages/[conversationId]">) {
  const { conversationId } = await props.params;
  const me = await requireProfile();
  // Messages load alongside the conversation: RLS only returns them to members.
  const [conv, messages] = await Promise.all([getConversation(conversationId, me.id), getMessages(conversationId)]);
  if (!conv) notFound();

  // People who have left the group but are still named here — as senders, or
  // in system lines ("Salman removed Tania").
  const known = new Set(conv.members.map((m) => m.id));
  const former = conv.isGroup
    ? await getPeers([...new Set(messages.flatMap(eventPeople).filter((id) => !known.has(id)))])
    : [];
  const others = conv.members.filter((m) => m.id !== me.id);

  return (
    <div className="mx-auto flex max-w-2xl flex-col">
      {conv.isGroup ? (
        <PageHeader
          title={conv.title}
          subtitle={`${conv.members.length} ${conv.members.length === 1 ? "member" : "members"}`}
          back="/messages"
          action={
            <Link href={`/messages/${conv.id}/info`} aria-label="Group info" className="mr-1 flex items-center gap-2">
              <GroupAvatar members={others} size={34} />
              <Info className="size-5 text-ink-muted" />
            </Link>
          }
        />
      ) : (
        <PageHeader
          title={conv.peer!.full_name}
          subtitle={`@${conv.peer!.username}`}
          back="/messages"
          action={
            <Link href={`/u/${conv.peer!.username}`} className="mr-1">
              <Avatar path={conv.peer!.avatar_path} name={conv.peer!.full_name} size={34} />
            </Link>
          }
        />
      )}
      <ChatThread
        conversationId={conv.id}
        meId={me.id}
        peer={conv.peer}
        group={conv.isGroup ? { title: conv.title, memberCount: conv.members.length } : null}
        members={[...conv.members, ...former]}
        initialMessages={messages}
        blocked={conv.blocked}
      />
    </div>
  );
}
