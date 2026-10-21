import Link from "next/link";
import { Camera, Search } from "lucide-react";
import { redirect } from "next/navigation";
import { getUserId, requireProfile } from "@/lib/data/session";
import { getFollowingIds, maybeSuggestedPeople } from "@/lib/data/profiles";
import { PushBanner } from "@/components/push/PushBanner";
import { getFeedPage } from "@/lib/data/posts";
import { getStoryFeed } from "@/lib/data/stories";
import { FeedList } from "@/components/post/FeedList";
import { StoryBar } from "@/components/story/StoryBar";

export default async function HomePage() {
  const userId = await getUserId();
  if (!userId) redirect("/login");
  // The feed is keyed off the session, so it starts right away. Stories (and
  // the empty-state copy) still need the following list; that pair runs
  // alongside the feed instead of in front of it.
  const followingIds = getFollowingIds(userId);
  const [me, following, stories, { posts, nextCursor }, suggestions] = await Promise.all([
    requireProfile(),
    followingIds,
    followingIds.then((ids) => getStoryFeed(userId, ids)),
    getFeedPage(),
    // Suggestions appear on some feed loads, and only with 2+ people to show.
    maybeSuggestedPeople(userId),
  ]);

  return (
    <div className="mx-auto max-w-[520px] sm:px-4 sm:py-4">
      <StoryBar groups={stories} me={me} />
      <PushBanner userId={me.id} />

      {posts.length === 0 ? (
        <div className="card mx-4 mt-2 px-6 py-14 text-center sm:mx-0">
          <p className="font-display text-xl font-semibold">Your feed is quiet</p>
          <p className="mx-auto mt-2 max-w-xs text-sm text-ink-muted">
            {following.length === 0
              ? "Send a few follow requests — once accepted, their posts show up here. Or share the first one yourself."
              : "The people you follow haven't posted lately. Be the first."}
          </p>
          <div className="mt-6 flex flex-col justify-center gap-3 sm:flex-row">
            <Link href="/explore" className="btn-secondary">
              <Search className="size-4" /> Find people
            </Link>
            <Link href="/new" className="btn-primary">
              <Camera className="size-4" /> Create a post
            </Link>
          </div>
        </div>
      ) : (
        <FeedList
          initialPosts={posts}
          initialCursor={nextCursor}
          viewer={{ id: me.id, isAdmin: me.is_admin }}
          suggestions={suggestions.length >= 2 ? suggestions : undefined}
        />
      )}
    </div>
  );
}
