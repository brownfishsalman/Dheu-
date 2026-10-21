package com.salmansaadiq.dheu.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the Realtime socket open only while Dheu is on screen. Thirty seconds
 * after the app goes to the background the socket closes (a quick look at
 * another app keeps it); coming back rejoins every channel. Screens catch up on
 * whatever was sent meanwhile when their channel rejoins (see [rejoins]).
 */
object LiveConnection : DefaultLifecycleObserver {
    private val scope = MainScope()
    private var pendingClose: Job? = null
    private var closed = false

    fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    override fun onStop(owner: LifecycleOwner) {
        if (!Backend.configured) return
        pendingClose = scope.launch {
            delay(30.seconds)
            val realtime = Backend.client.realtime
            // If the network already dropped, supabase-kt's own retry loop owns the
            // socket and rejoins the channels itself once it gets through.
            if (realtime.status.value == Realtime.Status.CONNECTED) {
                realtime.disconnect()
                closed = true
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        pendingClose?.cancel()
        if (!closed) return
        closed = false
        scope.launch {
            for (channel in Backend.client.realtime.subscriptions.values.toList()) {
                if (channel.status.value == RealtimeChannel.Status.UNSUBSCRIBED) runCatching { channel.subscribe() }
            }
        }
    }
}

/**
 * Emits each time [channel] joins again after its first join: after a dropped
 * network or a stay in the background, so events may have been missed.
 */
fun rejoins(channel: RealtimeChannel): Flow<Unit> = flow {
    var joined = false
    channel.status.collect { status ->
        if (status == RealtimeChannel.Status.SUBSCRIBED) {
            if (joined) emit(Unit)
            joined = true
        }
    }
}
