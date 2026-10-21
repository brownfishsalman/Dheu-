package com.salmansaadiq.dheu.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object Stories {
    private val client get() = Backend.client

    /** Active stories from me and people I follow, grouped per author: mine, then unseen, then seen. */
    suspend fun feed(me: String, followingIds: List<String>): List<StoryGroup> {
        val rows = client.from("stories").select(
            Columns.raw(
                "id, author_id, image_path, width, height, created_at, expires_at, " +
                    "author:profiles!stories_author_id_fkey($PERSON), views:story_views(viewer_id)",
            ),
        ) {
            filter {
                isIn("author_id", listOf(me) + followingIds)
                gt("expires_at", nowIso())
            }
            order("created_at", Order.ASCENDING)
        }.decodeList<StoryRow>()

        val groups = LinkedHashMap<String, StoryGroup>()
        for (s in rows) {
            val author = s.author ?: continue
            val seen = s.views.any { it.viewerId == me }
            val story = Story(s.id, s.authorId, s.imagePath, s.width, s.height, s.createdAt, seen)
            val g = groups[s.authorId]
            groups[s.authorId] = if (g == null) StoryGroup(author, listOf(story), seen, s.createdAt)
            else g.copy(stories = g.stories + story, allSeen = g.allSeen && seen, latestAt = s.createdAt)
        }
        return groups.values.sortedWith(
            compareBy<StoryGroup> { it.author.id != me }.thenBy { it.allSeen }.thenByDescending { it.latestAt },
        )
    }

    suspend fun ofAuthor(authorId: String, me: String): StoryGroup? =
        feed(me, listOf(authorId)).firstOrNull { it.author.id == authorId }

    @Serializable
    private data class ViewInsert(@SerialName("story_id") val storyId: String, @SerialName("viewer_id") val viewerId: String)

    suspend fun markViewed(storyId: String, me: String) {
        client.from("story_views").upsert(ViewInsert(storyId, me)) {
            onConflict = "story_id,viewer_id"
            ignoreDuplicates = true
        }
    }

    @Serializable
    private data class ReactionUpsert(
        @SerialName("story_id") val storyId: String,
        @SerialName("user_id") val userId: String,
        val emoji: String,
    )

    suspend fun react(storyId: String, me: String, emoji: String) {
        client.from("story_reactions").upsert(ReactionUpsert(storyId, me, emoji)) { onConflict = "story_id,user_id" }
    }

    /** A text reply to a story lands in the author's DMs, with the story attached. */
    suspend fun reply(story: Story, me: String, text: String) {
        val conversationId = Chat.directConversation(story.authorId)
        Chat.insertMessage(MessageInsert(conversationId, me, text, storyId = story.id))
    }

    @Serializable
    private data class StoryInsert(
        @SerialName("author_id") val authorId: String,
        @SerialName("image_path") val imagePath: String,
        val width: Int,
        val height: Int,
    )

    suspend fun create(context: Context, me: String, uri: Uri) {
        val image = Images.encode(context, uri, Images.STORY)
        val path = Images.newPath(me)
        uploadFile(Buckets.STORIES, path, image.bytes, ContentType.Image.JPEG, CACHE_DAY)
        try {
            client.from("stories").insert(StoryInsert(me, path, image.width, image.height))
        } catch (e: Exception) {
            removeFiles(Buckets.STORIES, listOf(path))
            throw e
        }
    }

    suspend fun delete(story: Story) {
        client.from("stories").delete { filter { eq("id", story.id) } }
        removeFiles(Buckets.STORIES, listOf(story.imagePath))
    }

    @Serializable
    private data class ViewerRow(val viewer: Person? = null)

    /** Who has seen one of my stories (RLS: only the author can read others' views). */
    suspend fun seenBy(storyId: String): List<Person> =
        client.from("story_views").select(Columns.raw("viewer:profiles!story_views_viewer_id_fkey($PERSON)")) {
            filter { eq("story_id", storyId) }
            order("viewed_at", Order.DESCENDING)
        }.decodeList<ViewerRow>().mapNotNull { it.viewer }
}
