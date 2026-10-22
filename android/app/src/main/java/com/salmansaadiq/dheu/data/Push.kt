package com.salmansaadiq.dheu.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.salmansaadiq.dheu.BuildConfig
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Push notifications through Firebase Cloud Messaging. This phone's token goes
 * into push_devices (0020_mentions_app_push.sql), and the website's /api/push
 * sends to it whenever the database reports a notification, message or
 * announcement, the same way it sends Web Push to browsers.
 *
 * Firebase Messaging 25.1 deprecated getToken / deleteToken / onNewToken in
 * favour of registering with the Firebase installation ID, but hasn't documented
 * that flow yet. The token calls still work in 26.0, so the app keeps them until
 * it can move over.
 */
@Suppress("DEPRECATION")
object Push {
    /** False when the build had no android/app/google-services.json (see README). */
    val configured get() = BuildConfig.FIREBASE_APP_ID.isNotBlank()

    /** Starts Firebase with the values build.gradle.kts read out of google-services.json. */
    fun init(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
    }

    /** Android 13+ asks before an app may notify; older versions allow it unless switched off. */
    fun allowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** Tell the website where to reach this phone, for the member signed in on it. */
    suspend fun register(token: String? = null) {
        if (!configured) return
        val t = token ?: FirebaseMessaging.getInstance().token.await()
        Backend.db.rpc("register_push_device", buildJsonObject { put("p_token", t) })
    }

    /** Signing out or switching notifications off: the website stops sending to this phone (needs the session). */
    suspend fun unregister() {
        if (!configured) return
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        runCatching { Backend.client.from("push_devices").delete { filter { eq("token", token) } } }
    }

    /**
     * After [unregister]: a fresh token, so a push already on its way can't reach
     * whoever signs in next. Firebase hands out the new one straight away, so call
     * this once signed out (or switched off), when onNewToken won't register it.
     */
    suspend fun forgetToken() {
        if (!configured) return
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
