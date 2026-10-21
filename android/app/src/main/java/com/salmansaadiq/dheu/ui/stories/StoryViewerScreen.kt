package com.salmansaadiq.dheu.ui.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.salmansaadiq.dheu.core.timeAgo
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.data.Buckets
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.Stories
import com.salmansaadiq.dheu.data.StoryGroup
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.DheuSheet
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PersonRow
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.nav.StoryRoute
import com.salmansaadiq.dheu.ui.rememberLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DURATION_MS = 5_000L
private val REACTIONS = listOf("❤️", "😂", "😮", "😢", "🔥", "👏")

private class StoryData(val group: StoryGroup?, val feed: List<StoryGroup>)

@Composable
fun StoryViewerScreen(authorId: String) {
    val me = LocalAppState.current.userId
    val nav = LocalNav.current
    val loader = rememberLoader("story-$authorId") {
        // Feed order decides whose stories come next / before.
        val feed = Stories.feed(me, Profiles.followingIds(me))
        StoryData(feed.firstOrNull { it.author.id == authorId } ?: Stories.ofAuthor(authorId, me), feed)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready -> {
                val group = s.value.group
                if (group == null || group.stories.isEmpty()) {
                    LaunchedEffect(Unit) { nav.back() }
                } else {
                    val pos = s.value.feed.indexOfFirst { it.author.id == authorId }
                    // Skip people whose stories I've all seen when moving on, like Instagram.
                    val next = if (pos >= 0) s.value.feed.drop(pos + 1).let { rest -> rest.firstOrNull { !it.allSeen } ?: rest.firstOrNull() } else null
                    val prev = if (pos > 0) s.value.feed[pos - 1] else null
                    StoryPlayer(
                        group = group,
                        onNextGroup = { if (next != null) nav.replace(StoryRoute(next.author.id)) else nav.back() },
                        onPrevGroup = { if (prev != null) nav.replace(StoryRoute(prev.author.id)) },
                        onClose = nav::back,
                        onDeleted = { loader.reload() },
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryPlayer(group: StoryGroup, onNextGroup: () -> Unit, onPrevGroup: () -> Unit, onClose: () -> Unit, onDeleted: () -> Unit) {
    val me = LocalAppState.current.userId
    val nav = LocalNav.current
    val isOwner = group.author.id == me
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var index by remember(group.author.id) { mutableIntStateOf(group.stories.indexOfFirst { !it.seen }.coerceAtLeast(0)) }
    var progress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var seenBy by remember { mutableStateOf<List<Person>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val story = group.stories[index.coerceIn(0, group.stories.lastIndex)]
    val paused = holding || typing || seenBy != null || confirmDelete

    fun next() {
        if (index < group.stories.lastIndex) index++ else onNextGroup()
    }
    fun prev() {
        if (index > 0) index-- else onPrevGroup()
    }

    LaunchedEffect(story.id) {
        progress = 0f
        if (!isOwner) runCatching { Stories.markViewed(story.id, me) }
    }
    LaunchedEffect(story.id, paused) {
        while (progress < 1f) {
            delay(16)
            if (!paused) progress = (progress + 16f / DURATION_MS).coerceAtMost(1f)
        }
        next()
    }

    Box(
        Modifier.fillMaxSize().pointerInput(story.id) {
            // Hold to pause; a quick tap on the left goes back, on the right goes forward.
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = System.currentTimeMillis()
                holding = true
                val up = waitForUpOrCancellation()
                holding = false
                if (up != null && System.currentTimeMillis() - start < 250) {
                    if (down.position.x < size.width / 3f) prev() else next()
                }
            }
        },
    ) {
        AsyncImage(
            model = Backend.publicUrl(Buckets.STORIES, story.imagePath),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                group.stories.forEachIndexed { i, _ ->
                    LinearProgressIndicator(
                        progress = { if (i < index) 1f else if (i == index) progress else 0f },
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.35f),
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                        modifier = Modifier.weight(1f).height(2.5.dp),
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { nav.profile(group.author.username) }, verticalAlignment = Alignment.CenterVertically) {
                    Avatar(group.author.avatarPath, group.author.fullName, 32.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(group.author.username, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(timeAgo(story.createdAt), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                IconButton(onClick = onClose) { Icon(Lucide.X, "Close", tint = Color.White) }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp)) {
            if (isOwner) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).clickable {
                            scope.launch { seenBy = runCatching { Stories.seenBy(story.id) }.getOrDefault(emptyList()).filter { it.id != me } }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Lucide.Eye, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Seen by", color = Color.White)
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Lucide.Trash, "Delete story", tint = Color.White) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    REACTIONS.forEach { emoji ->
                        Text(emoji, fontSize = 26.sp, modifier = Modifier.clickable {
                            scope.launch {
                                toast(runCatching { Stories.react(story.id, me, emoji) }.fold({ "Sent $emoji" }, { it.friendly("Couldn't send") }))
                            }
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { if (it.length <= 2000) reply = it },
                        placeholder = { Text("Reply to ${group.author.fullName.split(" ").first()}…", color = Color.White.copy(alpha = 0.7f)) },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.6f), cursorColor = Color.White,
                        ),
                        // Pause the story while the reply box has focus.
                        modifier = Modifier.weight(1f).onFocusChanged { typing = it.isFocused },
                    )
                    IconButton(
                        enabled = reply.isNotBlank(),
                        onClick = {
                            val text = reply.trim()
                            reply = ""
                            scope.launch {
                                toast(runCatching { Stories.reply(story, me, text) }.fold({ "Reply sent" }, { it.friendly("Couldn't send") }))
                            }
                        },
                    ) { Icon(Lucide.Send, "Send reply", tint = Color.White) }
                }
            }
        }
    }

    seenBy?.let { viewers ->
        DheuSheet("Seen by ${viewers.size}", onDismiss = { seenBy = null }) {
            if (viewers.isEmpty()) Text("No one yet.")
            LazyColumn { items(viewers, key = { it.id }) { PersonRow(it, relationships = null) } }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete this story?",
            "It will be removed for everyone, and from any highlight it's in.",
            "Delete",
            onConfirm = {
                scope.launch {
                    try {
                        Stories.delete(story)
                        onDeleted()
                    } catch (e: Exception) {
                        toast(e.friendly("Couldn't delete the story."))
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
