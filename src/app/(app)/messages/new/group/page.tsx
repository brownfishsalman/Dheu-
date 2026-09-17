import type { Metadata } from "next";
import { requireProfile } from "@/lib/data/session";
import { searchProfiles } from "@/lib/data/profiles";
import { PageHeader } from "@/components/ui/PageHeader";
import { PeoplePicker } from "@/components/chat/PeoplePicker";
import { GROUP_MAX_MEMBERS } from "@/lib/constants";

export const metadata: Metadata = { title: "New group" };

export default async function NewGroupPage() {
  const me = await requireProfile();
  // Everyone I can see (people who blocked me are hidden by RLS).
  const people = (await searchProfiles("", GROUP_MAX_MEMBERS * 2)).filter((p) => p.id !== me.id);

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="New group" subtitle="Pick at least two people" back="/messages/new" />
      <PeoplePicker mode="create" people={people} />
    </div>
  );
}
