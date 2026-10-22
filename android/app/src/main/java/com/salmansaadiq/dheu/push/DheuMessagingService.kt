package com.salmansaadiq.dheu.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.data.Push
import com.salmansaadiq.dheu.data.PushPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives pushes from Firebase, also while the app is closed. */
class DheuMessagingService : FirebaseMessagingService() {
    // The website sends data-only messages, so the app builds every notification itself.
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data.isNotEmpty()) Notifications.show(applicationContext, message.data)
    }

    // First run, a reinstall, or Firebase rotating the token: hand the new one to
    // the website if someone is signed in and hasn't switched notifications off.
    // (Deprecated with the token calls; see Push.)
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        scope.launch {
            runCatching {
                if (!Backend.configured) return@runCatching
                Backend.auth.awaitInitialization()
                if (Backend.userId != null && PushPrefs.enabledNow(applicationContext)) Push.register(token)
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
