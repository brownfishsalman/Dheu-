import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getUserId } from "@/lib/data/session";
import { getFollowingIds } from "@/lib/data/profiles";
import { getStoryFeed, getAuthorStories, getHighlights } from "@/lib/data/stories";
import { StoryViewer } from "@/components/story/StoryViewer";

export const metadata: Metadata = { title: "Story" };

export default async function StoryPage(props: PageProps<"/stories/[authorId]">) {
  const { authorId } = await props.params;
  // Only the id is needed here (the layout loads the full profile), so the
  // following list can be fetched straight away.
  const userId = await getUserId();
  if (!userId) redirect("/login");
  const isOwner = authorId === userId;
  const following = await getFollowingIds(userId);

  // Feed order decides which person's stories come next / previous.
  const [feed, highlights] = await Promise.all([
    getStoryFeed(userId, following),
    isOwner ? getHighlights(userId) : Promise.resolve([]),
  ]);
  let group = feed.find((g) => g.author.id === authorId) ?? null;
  const pos = feed.findIndex((g) => g.author.id === authorId);

  if (!group) group = await getAuthorStories(authorId, userId);
  if (!group || group.stories.length === 0) redirect(isOwner ? "/stories/new" : "/");

  const myHighlights = highlights.map((h) => ({ id: h.id, title: h.title }));

  // Skip fully-seen groups when auto-advancing to keep the flow like Instagram.
  const nextGroup = pos >= 0 ? feed.slice(pos + 1).find((g) => !g.allSeen) ?? feed[pos + 1] : undefined;
  const prevGroup = pos > 0 ? feed[pos - 1] : undefined;

  return (
    <StoryViewer
      group={group}
      viewerId={userId}
      isOwner={isOwner}
      mode="story"
      closeHref="/"
      nextHref={nextGroup ? `/stories/${nextGroup.author.id}` : null}
      prevHref={prevGroup ? `/stories/${prevGroup.author.id}` : null}
      myHighlights={myHighlights}
      startIndex={Math.max(0, group.stories.findIndex((s) => !s.seen))}
    />
  );
}
