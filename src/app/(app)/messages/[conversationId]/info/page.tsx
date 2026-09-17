import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { requireProfile } from "@/lib/data/session";
import { getConversation } from "@/lib/data/chat";
import { PageHeader } from "@/components/ui/PageHeader";
import { GroupAvatar } from "@/components/chat/GroupAvatar";
import { GroupInfo } from "@/components/chat/GroupInfo";

export async function generateMetadata(props: PageProps<"/messages/[conversationId]/info">): Promise<Metadata> {
  const { conversationId } = await props.params;
  const me = await requireProfile();
  const conv = await getConversation(conversationId, me.id);
  return { title: conv ? `${conv.title} · Group info` : "Group info" };
}

export default async function GroupInfoPage(props: PageProps<"/messages/[conversationId]/info">) {
  const { conversationId } = await props.params;
  const me = await requireProfile();
  const conv = await getConversation(conversationId, me.id);
  if (!conv) notFound();
  if (!conv.isGroup) redirect(`/messages/${conv.id}`); // DMs have no info page

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader
        title="Group info"
        back={`/messages/${conv.id}`}
        action={<GroupAvatar members={conv.members.filter((m) => m.id !== me.id)} size={34} className="mr-1" />}
      />
      <GroupInfo conversationId={conv.id} title={conv.title} members={conv.members} ownerId={conv.ownerId} meId={me.id} />
    </div>
  );
}
