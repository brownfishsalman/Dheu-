package com.salmansaadiq.dheu.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.CircleCropTransformation
import com.salmansaadiq.dheu.MainActivity
import com.salmansaadiq.dheu.R
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** The chat open on screen right now, so its messages don't also pop up as notifications. */
object Foreground {
    @Volatile
    var openChat: String? = null

    fun isShowing(url: String): Boolean {
        val chat = openChat ?: return false
        return url == "/messages/$chat" && ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
}

/**
 * Notifications built from a push's data: title, body, url (a website path
 * such as /messages/<id>), tag (a newer push with the same tag replaces the
 * older one, as in the browser) and icon (the sender's photo).
 */
object Notifications {
    const val EXTRA_URL = "com.salmansaadiq.dheu.URL"
    private const val MESSAGES = "messages"
    private const val ACTIVITY = "activity"
    private const val ANNOUNCEMENTS = "announcements"
    private const val BRAND = 0xFF1589A5.toInt()

    /** One channel per kind, so members can mute activity but keep messages (or the other way round). */
    fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Messages").setDescription("New messages in your chats and groups").build(),
                NotificationChannelCompat.Builder(ACTIVITY, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Activity").setDescription("Likes, comments, mentions, follows and story reactions").build(),
                NotificationChannelCompat.Builder(ANNOUNCEMENTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Announcements").setDescription("News from Dheu's admin").build(),
            ),
        )
    }

    private fun channelFor(url: String) = when {
        url.startsWith("/messages/announcements") -> ANNOUNCEMENTS
        url.startsWith("/messages/") -> MESSAGES
        else -> ACTIVITY
    }

    /** Runs on Firebase's background thread, so waiting briefly for the sender's photo is fine. */
    fun show(context: Context, data: Map<String, String>) {
        val url = data["url"] ?: "/"
        if (Foreground.isShowing(url)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val tag = data["tag"] ?: url
        val open = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_URL, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val tap = PendingIntent.getActivity(context, tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val channel = channelFor(url)
        val body = data["body"].orEmpty()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_dheu)
            .setColor(BRAND)
            .setContentTitle(data["title"] ?: "Dheu")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (channel == MESSAGES) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .apply { photo(context, data["icon"])?.let(::setLargeIcon) }
            .build()
        try {
            manager.notify(tag, 0, notification)
        } catch (_: SecurityException) {
            // Notifications were switched off between the check and now.
        }
    }

    /** Clear a chat's notification once it's open. */
    fun clearChat(context: Context, conversationId: String) {
        NotificationManagerCompat.from(context).cancel("chat-$conversationId", 0)
    }

    private fun photo(context: Context, url: String?): Bitmap? {
        if (url == null) return null
        return runCatching {
            runBlocking {
                withTimeoutOrNull(4_000) {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .size(192)
                        .allowHardware(false) // notifications can't draw hardware bitmaps
                        .transformations(CircleCropTransformation())
                        .build()
                    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
                }
            }
        }.getOrNull()
    }
}
