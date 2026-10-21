package com.salmansaadiq.dheu.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.http.ContentType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/** Only the columns members may insert (0012 grants exactly these). */
@Serializable
data class MessageInsert(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String,
    @SerialName("story_id") val storyId: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("note_text") val noteText: String? = null,
)

sealed interface ChatEvent {
    data class Inserted(val id: String) : ChatEvent
    data class Deleted(val id: String) : ChatEvent
}

object Chat {
    const val PAGE = 50
    private const val MEMBER_EMBED =
        "members:conversation_members(joined_at, user:profiles!conversation_members_user_id_fkey($PERSON))"
    private val client get() = Backend.client

    /** Members I can see, oldest first; someone who blocked me comes back null and is left out. */
    private fun visible(rows: List<MemberRow>) = rows.sortedBy { it.joinedAt }.mapNotNull { it.user }

    suspend fun inbox(me: String): List<ConversationSummary> {
        val rows = client.from("conversations").select(
            Columns.raw(
                "id, is_group, title, last_message_at, $MEMBER_EMBED, reads:conversation_reads(cleared_at), " +
                    "messages(body, sender_id, created_at, story_id, image_path, audio_path, note_text, kind, " +
                    "sender:profiles!messages_sender_id_fkey(full_name))",
            ),
        ) {
            order("last_message_at", Order.DESCENDING, nullsFirst = false)
            order("created_at", Order.DESCENDING, referencedTable = "messages")
            limit(1, referencedTable = "messages")
        }.decodeList<ConversationRow>()
        val unread = Backend.db.rpc("unread_counts").decodeList<UnreadRow>().associate { it.conversationId to it.unread.toInt() }

        return rows.mapNotNull { c ->
            val members = visible(c.members)
            val peer = if (c.isGroup) null else members.firstOrNull { it.id != me }
            if (!c.isGroup && peer == null) return@mapNotNull null // DM partner hidden (blocked me) or gone
            val last = c.messages.firstOrNull()
            // I deleted this chat and nothing new has come since (RLS hides older messages).
            if (c.reads.firstOrNull()?.clearedAt != null && last == null) return@mapNotNull null
            ConversationSummary(
                id = c.id,
                isGroup = c.isGroup,
                title = if (c.isGroup) c.title ?: "Group" else peer!!.fullName,
                peer = peer,
                members = members.filter { it.id != me },
                memberCount = c.members.size,
                last = last,
                unread = unread[c.id] ?: 0,
            )
        }
    }

    suspend fun conversation(id: String, me: String): ConversationInfo? {
        val c = client.from("conversations").select(Columns.raw("id, is_group, title, created_by, $MEMBER_EMBED")) {
            filter { eq("id", id) }
        }.decodeSingleOrNull<ConversationRow>() ?: return null
        val members = visible(c.members)
        if (c.isGroup) return ConversationInfo(c.id, true, c.title ?: "Group", null, members, null)
        val peer = members.firstOrNull { it.id != me } ?: return null
        val block = Backend.db.rpc("block_status", buildJsonObject { put("p_other", peer.id) }).decodeAs<String?>()
        return ConversationInfo(c.id, false, peer.fullName, peer, members, block)
    }

    /** Newest page (or the page before [before]), returned oldest first. */
    suspend fun messages(conversationId: String, before: String? = null): List<Message> =
        client.from("messages").select(Columns.raw(MESSAGE_SELECT)) {
            filter {
                eq("conversation_id", conversationId)
                if (before != null) lt("created_at", before)
            }
            order("created_at", Order.DESCENDING)
            limit(PAGE.toLong())
        }.decodeList<Message>().reversed()

    suspend fun message(id: String): Message? =
        client.from("messages").select(Columns.raw(MESSAGE_SELECT)) { filter { eq("id", id) } }.decodeSingleOrNull()

    suspend fun people(ids: Collection<String>): List<Person> {
        if (ids.isEmpty()) return emptyList()
        return client.from("profiles").select(Columns.raw(PERSON)) { filter { isIn("id", ids.toList()) } }.decodeList()
    }

    suspend fun insertMessage(message: MessageInsert): Message =
        client.from("messages").insert(message) { select(Columns.raw(MESSAGE_SELECT)) }.decodeSingle()

    /** Uploads the photo first (if any); if the message can't be saved, the file is removed again. */
    suspend fun send(conversationId: String, me: String, body: String, photo: Images.Encoded?, replyToId: String?): Message {
        val imagePath = photo?.let {
            val path = Images.newPath(me)
            uploadFile(Buckets.CHAT, path, it.bytes, ContentType.Image.JPEG, CACHE_WEEK)
            path
        }
        return try {
            insertMessage(MessageInsert(conversationId, me, body, imagePath = imagePath, replyToId = replyToId))
        } catch (e: Exception) {
            if (imagePath != null) removeFiles(Buckets.CHAT, listOf(imagePath))
            throw e
        }
    }

    /** Unsend my message for everyone, then its photo / voice file. False if nothing was deleted. */
    suspend fun unsend(m: Message): Boolean {
        val deleted = client.from("messages").delete {
            select(Columns.raw("id"))
            filter { eq("id", m.id) }
        }.decodeList<IdRow>()
        if (deleted.isEmpty()) return false
        removeFiles(Buckets.CHAT, listOfNotNull(m.imagePath, m.audioPath))
        return true
    }

    suspend fun markRead(conversationId: String) {
        Backend.db.rpc("mark_conversation_read", buildJsonObject { put("p_conversation_id", conversationId) })
    }

    /** "Delete chat": for me only; the others keep theirs (delete_chat_for_me). */
    suspend fun deleteForMe(conversationId: String) {
        Backend.db.rpc("delete_chat_for_me", buildJsonObject { put("p_conversation_id", conversationId) })
    }

    /** Find or create my 1-to-1 conversation with someone. */
    suspend fun directConversation(otherId: String): String =
        Backend.db.rpc("get_or_create_conversation", buildJsonObject { put("p_other_id", otherId) }).decodeAs<String>()

    suspend fun announcementSummary(): Pair<Announcement?, Int> {
        val latest = client.from("announcements").select(Columns.raw("id, body, created_at, author_id")) {
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeSingleOrNull<Announcement>()
        val unread = Backend.db.rpc("unread_announcement_count").decodeAs<Int>()
        return latest to unread
    }

    suspend fun announcements(): List<Announcement> =
        client.from("announcements").select(Columns.raw("id, body, created_at, author_id")) {
            order("created_at", Order.DESCENDING)
            limit(100)
        }.decodeList<Announcement>().reversed()

    suspend fun markAnnouncementsRead() {
        Backend.db.rpc("mark_announcements_read")
    }

    /**
     * New and unsent messages in one conversation. Inserts are filtered to this
     * conversation; Realtime can't filter DELETE events (they carry only the id),
     * so every unsend arrives and the screen keeps the ones it holds.
     */
    fun events(conversationId: String): Flow<ChatEvent> = channelFlow {
        val channel = client.channel("conversation:$conversationId:${Random.nextInt(1_000_000)}")
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>("public") {
            table = "messages"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        val deletes = channel.postgresChangeFlow<PostgresAction.Delete>("public") { table = "messages" }
        launch { inserts.collect { a -> a.record.string("id")?.let { send(ChatEvent.Inserted(it)) } } }
        launch { deletes.collect { a -> a.oldRecord.string("id")?.let { send(ChatEvent.Deleted(it)) } } }
        channel.subscribe()
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { client.realtime.removeChannel(channel) }
        }
    }
}
