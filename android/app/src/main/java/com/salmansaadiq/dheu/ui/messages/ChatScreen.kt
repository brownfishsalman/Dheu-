package com.salmansaadiq.dheu.ui.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.salmansaadiq.dheu.core.clock
import com.salmansaadiq.dheu.core.dayLabel
import com.salmansaadiq.dheu.core.daysLeft
import com.salmansaadiq.dheu.core.localDate
import com.salmansaadiq.dheu.core.minutesBetween
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.data.Buckets
import com.salmansaadiq.dheu.data.Message
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.replyLabel
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.DheuSheet
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.SheetAction
import com.salmansaadiq.dheu.ui.components.fieldColors
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.theme.Dheu

private const val FORMER = "Former member"

@Composable
fun ChatScreen(conversationId: String) {
    val app = LocalAppState.current
    val nav = LocalNav.current
    val vm: ChatViewModel = viewModel(key = "chat-$conversationId") { ChatViewModel(conversationId, app.userId) }
    // Leaving the chat may have changed what's unread.
    DisposableEffect(Unit) { onDispose { app.refreshBadges() } }

    val info = vm.info
    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar(
            title = info?.title ?: "Messages",
            subtitle = when {
                info == null -> null
                info.isGroup -> "${info.members.size} ${if (info.members.size == 1) "member" else "members"}"
                else -> "@${info.peer?.username}"
            },
            onBack = nav::back,
            actions = {
                if (info != null) {
                    Box(Modifier.padding(end = 10.dp).clickable { info.peer?.let { nav.profile(it.username) } }) {
                        if (info.isGroup) GroupAvatar(info.members.filter { it.id != app.userId }, 34.dp)
                        else info.peer?.let { Avatar(it.avatarPath, it.fullName, 34.dp) }
                    }
                }
            },
        )
        when {
            vm.loading -> Loading()
            vm.missing -> ErrorState("This conversation isn't available.", onRetry = null)
            info == null -> ErrorState(vm.error ?: "Couldn't open this chat.", onRetry = null)
            else -> ChatBody(vm, info.isGroup, canSend = info.blockStatus == null, blockedByMe = info.blockStatus == "by_me" || info.blockStatus == "both")
        }
    }
}

@Composable
private fun ChatBody(vm: ChatViewModel, isGroup: Boolean, canSend: Boolean, blockedByMe: Boolean) {
    val me = LocalAppState.current.userId
    val context = LocalContext.current
    val toast = rememberToast()
    val c = Dheu.colors
    val list = rememberLazyListState()
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var confirmUnsend by remember { mutableStateOf<Message?>(null) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var lightbox by remember { mutableStateOf<String?>(null) }

    fun nameOf(id: String) = if (id == me) "You" else vm.people[id]?.fullName ?: FORMER

    // Newest at the bottom: the list is laid out bottom-up, so it stays pinned there.
    val reversed = vm.messages.asReversed()
    LaunchedEffect(vm.messages.lastOrNull()?.id) { if (list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0) }
    LaunchedEffect(vm.error) { vm.error?.let { toast(it); vm.error = null } }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state = list, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            itemsIndexed(reversed, key = { _, m -> m.id }) { i, m ->
                val index = vm.messages.lastIndex - i // position in chronological order
                val prev = vm.messages.getOrNull(index - 1)
                val next = vm.messages.getOrNull(index + 1)
                val showDay = prev == null || localDate(prev.createdAt) != localDate(m.createdAt)
                Column {
                    if (showDay) {
                        Text(dayLabel(m.createdAt), color = c.inkFaint, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    }
                    if (m.isSystem) {
                        Text(systemText(m, ::nameOf), color = c.inkFaint, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 24.dp))
                    } else {
                        val grouped = prev != null && !prev.isSystem && prev.senderId == m.senderId && !showDay && minutesBetween(prev.createdAt, m.createdAt) < 5
                        val endOfRun = next == null || next.isSystem || next.senderId != m.senderId ||
                            localDate(next.createdAt) != localDate(m.createdAt) || minutesBetween(m.createdAt, next.createdAt) >= 5
                        Bubble(
                            m = m,
                            mine = m.senderId == me,
                            grouped = grouped,
                            sender = if (isGroup && m.senderId != me) vm.people[m.senderId] else null,
                            senderName = nameOf(m.senderId),
                            showName = isGroup && m.senderId != me && !grouped,
                            showAvatar = isGroup && m.senderId != me && endOfRun,
                            isGroup = isGroup,
                            nameOf = ::nameOf,
                            onLongPress = { actionsFor = m },
                            onOpenImage = { lightbox = it },
                        )
                    }
                }
            }
            if (vm.hasOlder) {
                item(key = "older") {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        if (vm.loadingOlder) CircularProgressIndicator(Modifier.size(20.dp), color = c.inkFaint, strokeWidth = 2.dp)
                        else TextButton(onClick = vm::loadOlder) { Text("Load earlier messages", color = c.inkMuted) }
                    }
                }
            }
        }

        HorizontalDivider(color = c.line)
        if (!canSend) {
            Text(
                if (blockedByMe) "You blocked this member. Unblock them in Settings to message again." else "You can't message this member.",
                color = c.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            )
        } else {
            Composer(vm, replyTo, onCancelReply = { replyTo = null }, nameOf = ::nameOf, onSent = { replyTo = null })
        }
    }

    actionsFor?.let { m ->
        DheuSheet(if (m.senderId == me) "Your message" else "Message", onDismiss = { actionsFor = null }) {
            if (canSend) SheetAction(Lucide.Reply, "Reply", { replyTo = m; actionsFor = null })
            if (m.body.isNotEmpty()) SheetAction(Lucide.Copy, "Copy text", {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Message", m.body))
                actionsFor = null
            })
            if (m.senderId == me && !m.isSystem) SheetAction(Lucide.Undo, "Unsend", { confirmUnsend = m; actionsFor = null }, danger = true)
        }
    }
    confirmUnsend?.let { m ->
        ConfirmDialog(
            "Unsend this message?",
            "It will be removed for everyone.",
            "Unsend",
            onConfirm = {
                if (replyTo?.id == m.id) replyTo = null
                vm.unsend(m)
            },
            onDismiss = { confirmUnsend = null },
        )
    }
    lightbox?.let { url ->
        Dialog(onDismissRequest = { lightbox = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)).clickable { lightbox = null }, contentAlignment = Alignment.Center) {
                AsyncImage(url, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** "You added Rafi, Tania": rendered from the event so the viewer's own name becomes "You". */
private fun systemText(m: Message, nameOf: (String) -> String): String {
    val e = m.event ?: return m.body
    val actor = nameOf(e.actor)
    val targets = e.targets.joinToString(", ") { nameOf(it) }.ifEmpty { "someone" }
    return when (e.type) {
        "created" -> "$actor created the group"
        "added" -> "$actor added $targets"
        "removed" -> "$actor removed $targets"
        "left" -> "$actor left"
        "renamed" -> "$actor renamed the group to “${e.title.orEmpty()}”"
        else -> m.body
    }
}

@Composable
private fun Bubble(
    m: Message,
    mine: Boolean,
    grouped: Boolean,
    sender: Person?,
    senderName: String,
    showName: Boolean,
    showAvatar: Boolean,
    isGroup: Boolean,
    nameOf: (String) -> String,
    onLongPress: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val c = Dheu.colors
    val nav = LocalNav.current
    val bg = if (mine) c.brand else c.surface2
    val fg = if (mine) c.brandInk else c.ink
    val shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 6.dp else 18.dp, if (mine) 18.dp else 6.dp)
    val image = Backend.publicUrl(Buckets.CHAT, m.imagePath)
    val audio = Backend.publicUrl(Buckets.CHAT, m.audioPath)
    val mediaExpiresAt = if (image != null) m.imageExpiresAt else if (audio != null) m.audioExpiresAt else null

    Row(
        Modifier.fillMaxWidth().padding(top = if (grouped) 2.dp else 8.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isGroup && !mine) {
            Box(Modifier.width(36.dp)) { if (showAvatar) Avatar(sender?.avatarPath, sender?.fullName ?: senderName, 28.dp) }
        }
        Column(Modifier.widthIn(max = 290.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (showName) Text(senderName, color = c.inkMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
            m.story?.let { story ->
                Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(
                        Backend.publicUrl(Buckets.STORIES, story.imagePath),
                        null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(48.dp).height(80.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2).clickable { nav.story(story.authorId) },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (mine) "You replied to their story" else "Replied to your story", color = c.inkFaint, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (m.storyId != null && m.story == null) Text("Replied to a story (expired)", color = c.inkFaint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
            m.noteText?.let { note ->
                Text(if (mine) "You replied to their note" else "Replied to your note", color = c.inkFaint, style = MaterialTheme.typography.labelSmall)
                Text(note, color = c.inkMuted, modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Column(
                Modifier.clip(shape).background(bg).combinedClickable(onClick = {}, onLongClick = onLongPress),
            ) {
                m.replyPreview?.let { r ->
                    Column(
                        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (mine) Color.Black.copy(alpha = 0.12f) else c.surface).padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(nameOf(r.senderId), color = if (mine) fg else c.brand, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(replyLabel(r), color = fg.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (image != null) {
                    AsyncImage(
                        image,
                        "Photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.heightIn(max = 320.dp).fillMaxWidth().combinedClickable(onClick = { onOpenImage(image) }, onLongClick = onLongPress),
                    )
                }
                if (m.imagePath == null && m.imageExpiresAt != null) Expired("Photo expired", fg)
                if (audio != null) VoiceNote(audio, m.audioDurationMs, tint = fg, track = fg.copy(alpha = 0.3f))
                if (m.audioPath == null && m.audioExpiresAt != null) Expired("Voice message expired", fg)
                if (m.body.isNotEmpty()) Text(m.body, color = fg, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                if (mediaExpiresAt != null) {
                    Text(
                        "disappears in ${daysLeft(mediaExpiresAt)}d · ${clock(m.createdAt)}",
                        color = fg.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Expired(label: String, color: Color) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.Clock, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color.copy(alpha = 0.7f), fontStyle = FontStyle.Italic)
    }
}

@Composable
private fun Composer(vm: ChatViewModel, replyTo: Message?, onCancelReply: () -> Unit, nameOf: (String) -> String, onSent: () -> Unit) {
    val c = Dheu.colors
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().background(c.surface2.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Reply, null, tint = c.brand, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(nameOf(r.senderId), color = c.brand, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(replyLabel(r.toReplyPreview()), color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
                IconButton(onClick = onCancelReply) { Icon(Lucide.X, "Cancel reply", tint = c.inkMuted, modifier = Modifier.size(18.dp)) }
            }
        }
        photo?.let { uri ->
            Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(uri, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(10.dp))
                Text("Disappears 7 days after sending.", color = c.inkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { photo = null }) { Icon(Lucide.X, "Remove photo", tint = c.inkMuted) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !vm.sending) {
                Icon(Lucide.ImagePlus, "Send a photo", tint = c.inkMuted)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 2000) text = it },
                placeholder = { Text(if (photo != null) "Add a caption…" else "Message…") },
                shape = RoundedCornerShape(24.dp),
                colors = fieldColors(),
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            val canSend = (text.isNotBlank() || photo != null) && !vm.sending
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (canSend) c.brand else c.surface2).clickable(enabled = canSend) {
                    vm.send(context, text, photo, replyTo) { ok ->
                        if (ok) {
                            text = ""
                            photo = null
                            onSent()
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                if (vm.sending) CircularProgressIndicator(Modifier.size(20.dp), color = c.brandInk, strokeWidth = 2.dp)
                else Icon(Lucide.Send, "Send", tint = if (canSend) c.brandInk else c.inkFaint, modifier = Modifier.size(20.dp))
            }
        }
    }
}
