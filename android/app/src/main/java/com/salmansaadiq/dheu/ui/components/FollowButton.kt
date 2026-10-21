package com.salmansaadiq.dheu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.salmansaadiq.dheu.data.FollowState
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.LocalAppState
import kotlinx.coroutines.launch

/** Follow (instant for public accounts, a request for private ones), cancel a request, or unfollow. */
@Composable
fun FollowButton(targetId: String, initial: FollowState, onChange: (FollowState) -> Unit = {}) {
    val me = LocalAppState.current.userId
    var state by remember(targetId, initial) { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var confirmUnfollow by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    fun run(action: suspend () -> FollowState) {
        busy = true
        scope.launch {
            try {
                state = action()
                onChange(state)
            } catch (e: Exception) {
                toast(e.friendly())
            } finally {
                busy = false
            }
        }
    }

    when (state) {
        FollowState.None -> PrimaryButton("Follow", { run { Profiles.follow(targetId) } }, loading = busy, compact = true)
        FollowState.Requested -> SecondaryButton(
            "Requested",
            { run { Profiles.cancelRequest(me, targetId); FollowState.None } },
            loading = busy,
            compact = true,
        )
        FollowState.Following -> SecondaryButton("Following", { confirmUnfollow = true }, loading = busy, compact = true)
    }

    if (confirmUnfollow) {
        ConfirmDialog(
            title = "Unfollow?",
            text = "If their account is private, you'll need to send a new request to follow them again.",
            confirmLabel = "Unfollow",
            onConfirm = { run { Profiles.unfollow(me, targetId); FollowState.None } },
            onDismiss = { confirmUnfollow = false },
        )
    }
}
