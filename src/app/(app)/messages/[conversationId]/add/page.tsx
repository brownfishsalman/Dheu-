import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { requireProfile } from "@/lib/data/session";
import { getConversation } from "@/lib/data/chat";
import { searchProfiles } from "@/lib/data/profiles";
import { PageHeader } from "@/components/ui/PageHeader";
import { PeoplePicker } from "@/components/chat/PeoplePicker";
import { GROUP_MAX_MEMBERS } from "@/lib/constants";

export const metadata: Metadata = { title: "Add people" };

export default async function AddPeoplePage(props: PageProps<"/messages/[conversationId]/add">) {
  const { conversationId } = await props.params;
  const me = await requireProfile();
  const conv = await getConversation(conversationId, me.id);
  if (!conv) notFound();
  if (!conv.isGroup) redirect(`/messages/${conv.id}`);

  const already = new Set(conv.members.map((m) => m.id));
  const people = (await searchProfiles("", GROUP_MAX_MEMBERS * 2)).filter((p) => p.id !== me.id && !already.has(p.id));
  const roomLeft = Math.max(0, GROUP_MAX_MEMBERS - conv.members.length);

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="Add people" subtitle={conv.title} back={`/messages/${conv.id}/info`} />
      {roomLeft === 0 ? (
        <p className="px-4 py-12 text-center text-sm text-ink-muted">This group is full ({GROUP_MAX_MEMBERS} people).</p>
      ) : (
        <PeoplePicker mode="add" people={people} conversationId={conv.id} roomLeft={roomLeft} />
      )}
    </div>
  );
}
