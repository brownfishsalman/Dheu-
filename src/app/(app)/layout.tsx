import { redirect } from "next/navigation";
import { getUserId, requireProfile } from "@/lib/data/session";
import { getBadgeCounts } from "@/lib/data/badges";
import { Sidebar, TabBar, MobileHeader } from "@/components/nav/AppNav";
import { BadgeProvider } from "@/components/nav/BadgeProvider";
import { ChoosePrivacy } from "@/components/settings/AccountPrivacy";

export default async function AppLayout({ children }: LayoutProps<"/">) {
  const userId = await getUserId();
  if (!userId) redirect("/login");
  const [profile, counts] = await Promise.all([requireProfile(), getBadgeCounts()]);

  // Members from before accounts could be public pick one before going on.
  if (!profile.privacy_chosen_at) return <ChoosePrivacy />;

  return (
    <BadgeProvider userId={userId} initial={counts}>
      <div className="min-h-dvh">
        <Sidebar profile={profile} />
        <MobileHeader />

        {/* Content column: offset by the sidebar on desktop, by the tab bar on phones */}
        <main className="pb-[calc(3.5rem+env(safe-area-inset-bottom))] md:ml-[76px] md:pb-8 xl:ml-60">
          {children}
        </main>

        <TabBar profile={profile} />
      </div>
    </BadgeProvider>
  );
}
