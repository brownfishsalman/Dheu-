package com.salmansaadiq.dheu.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object Activity {
    private val client get() = Backend.client

    suspend fun notifications(me: String): List<NotificationRow> =
        client.from("notifications").select(
            Columns.raw(
                "id, type, read_at, created_at, post_id, story_id, actor:profiles!notifications_actor_id_fkey($PERSON), " +
                    "post:posts(images:post_images(path, thumb_path, width, height, position)), comment:comments(body), story:stories(image_path)",
            ),
        ) {
            filter { eq("user_id", me) }
            order("created_at", Order.DESCENDING)
            limit(100)
        }.decodeList<NotificationRow>().filter { it.actor != null }

    suspend fun markAllRead(me: String) {
        client.from("notifications").update({ set("read_at", nowIso()) }) {
            filter {
                eq("user_id", me)
                exact("read_at", null)
            }
        }
    }

    suspend fun accept(requesterId: String) {
        Backend.db.rpc("accept_follow_request", buildJsonObject { put("p_requester", requesterId) })
    }

    suspend fun decline(me: String, requesterId: String) {
        client.from("follow_requests").delete { filter { eq("requester_id", requesterId); eq("target_id", me) } }
    }

    suspend fun badges(): Badges = Backend.db.rpc("badge_counts").decodeList<Badges>().firstOrNull() ?: Badges()

    /**
     * Anything that may change a badge: new messages or unsends, announcements,
     * my notifications appearing, any notification removed (DELETE events can't
     * be filtered, so callers should debounce). Also fires when the channel
     * rejoins after a gap, since events may have been missed.
     */
    fun badgeEvents(me: String): Flow<Unit> = channelFlow {
        val channel = client.channel("badges:$me")
        val flows = listOf(
            channel.postgresChangeFlow<PostgresAction.Insert>("public") { table = "messages" },
            channel.postgresChangeFlow<PostgresAction.Delete>("public") { table = "messages" },
            channel.postgresChangeFlow<PostgresAction.Insert>("public") { table = "announcements" },
            channel.postgresChangeFlow<PostgresAction.Insert>("public") {
                table = "notifications"
                filter("user_id", FilterOperator.EQ, me)
            },
            channel.postgresChangeFlow<PostgresAction.Delete>("public") { table = "notifications" },
        )
        launch { merge(*flows.toTypedArray(), rejoins(channel)).collect { send(Unit) } }
        channel.subscribe()
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { client.realtime.removeChannel(channel) }
        }
    }
}
