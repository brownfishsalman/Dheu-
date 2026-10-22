package com.salmansaadiq.dheu.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.salmansaadiq.dheu.data.Chat
import com.salmansaadiq.dheu.data.ConversationInfo
import com.salmansaadiq.dheu.data.GROUP_MAX_MEMBERS
import com.salmansaadiq.dheu.data.GROUP_MIN_OTHERS
import com.salmansaadiq.dheu.data.GROUP_TITLE_MAX
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.DheuTextField
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.components.SecondaryButton
import com.salmansaadiq.dheu.ui.components.fieldColors
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.AddMembersRoute
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.nav.MessagesRoute
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Groups, as on the website (messages/new/group, [id]/info, [id]/add). The
// database functions hold the rules: up to 50 people, at least two besides
// you to start one, any member renames or adds, only the owner removes.

/** Everyone I can see, for picking (people who blocked me are hidden by RLS). */
private suspend fun everyone(me: String) = Profiles.search("", GROUP_MAX_MEMBERS * 2L).filter { it.id != me }

@Composable
fun NewGroupScreen() {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val scope = rememberCoroutineScope()
    val loader = rememberLoader("new-group-people") { everyone(me) }
    var title by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun create() {
        busy = true
        error = null
        scope.launch {
            try {
                val id = Chat.createGroup(title, selected.toList())
                nav.backTo(MessagesRoute)
                nav.chat(id)
            } catch (e: Exception) {
                error = Chat.groupError(e)
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar("New group", subtitle = "Pick at least two people", onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready -> {
                DheuTextField(title, { title = it }, "Group name", maxLength = GROUP_TITLE_MAX, modifier = Modifier.padding(16.dp))
                PeoplePicker(
                    s.value,
                    selected,
                    limit = GROUP_MAX_MEMBERS - 1,
                    onChange = { selected = it; error = null },
                    onLimit = { error = it },
                    modifier = Modifier.weight(1f),
                )
                PickerFooter(
                    label = if (selected.isEmpty()) "Create group" else "Create group (${selected.size})",
                    enabled = selected.size >= GROUP_MIN_OTHERS && title.isNotBlank(),
                    busy = busy,
                    error = error,
                    onClick = ::create,
                )
            }
        }
    }
}

private class AddData(val info: ConversationInfo, val people: List<Person>)

@Composable
fun AddMembersScreen(conversationId: String) {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val scope = rememberCoroutineScope()
    val loader = rememberLoader("group-add-$conversationId") {
        coroutineScope {
            val people = async { everyone(me) }
            val info = Chat.conversation(conversationId, me) ?: return@coroutineScope null
            val already = info.members.map { it.id }.toSet()
            AddData(info, people.await().filter { it.id !in already })
        }
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Dheu.colors

    Column(Modifier.fillMaxSize().background(c.surface)) {
        val data = (loader.state as? Load.Ready)?.value
        DheuTopBar("Add people", subtitle = data?.info?.title, onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready -> {
                val d = s.value
                val room = (GROUP_MAX_MEMBERS - (d?.info?.members?.size ?: GROUP_MAX_MEMBERS)).coerceAtLeast(0)
                when {
                    d == null -> ErrorState("This group isn't available.", onRetry = null)
                    room == 0 -> Text(
                        "This group is full ($GROUP_MAX_MEMBERS people).",
                        color = c.inkMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
                    )
                    else -> {
                        PeoplePicker(d.people, selected, limit = room, onChange = { selected = it; error = null }, onLimit = { error = it }, modifier = Modifier.weight(1f))
                        PickerFooter(
                            label = if (selected.isEmpty()) "Add" else "Add ${selected.size}",
                            enabled = selected.isNotEmpty(),
                            busy = busy,
                            error = error,
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        Chat.addMembers(conversationId, selected.toList())
                                        nav.back()
                                    } catch (e: Exception) {
                                        error = Chat.groupError(e)
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private class GroupData(val info: ConversationInfo, val ownerId: String?)

@Composable
fun GroupInfoScreen(conversationId: String) {
    val nav = LocalNav.current
    val app = LocalAppState.current
    val me = app.userId
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val c = Dheu.colors
    val loader = rememberLoader("group-info-$conversationId") {
        coroutineScope {
            val owner = async { Chat.owner(conversationId) }
            Chat.conversation(conversationId, me)?.takeIf { it.isGroup }?.let { GroupData(it, owner.await()) }
        }
    }
    // Coming back from "Add people": show who joined.
    LaunchedEffect(Unit) { if (loader.state is Load.Ready) loader.reload(silent = true) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Person?>(null) }
    var leaving by remember { mutableStateOf(false) }

    fun run(fallbackError: String? = null, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                toast(fallbackError ?: Chat.groupError(e))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        DheuTopBar("Group info", onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready -> {
                val data = s.value
                if (data == null) {
                    ErrorState("This group isn't available.", onRetry = null)
                    return@Column
                }
                val info = data.info
                val isOwner = data.ownerId == me
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            GroupAvatar(info.members.filter { it.id != me }, 72.dp)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { renaming = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(info.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(6.dp))
                                Icon(Lucide.Pencil, "Rename group", tint = c.inkFaint, modifier = Modifier.size(16.dp))
                            }
                            Text("${info.members.size} ${if (info.members.size == 1) "member" else "members"}", color = c.inkMuted)
                        }
                        HorizontalDivider(color = c.line)
                    }
                    if (info.members.size < GROUP_MAX_MEMBERS) {
                        item(key = "add") {
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.to(AddMembersRoute(conversationId)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(44.dp).clip(CircleShape).background(c.brandSoft), contentAlignment = Alignment.Center) {
                                    Icon(Lucide.UserPlus, null, tint = c.brand, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text("Add people", color = c.brand, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    items(info.members, key = { it.id }) { p ->
                        val isMe = p.id == me
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.profile(p.username) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(p.avatarPath, p.fullName, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isMe) "You" else p.username, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (p.id == data.ownerId) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Lucide.ShieldCheck, "Group owner", tint = c.brand, modifier = Modifier.size(15.dp))
                                    }
                                }
                                Text(p.fullName, color = c.inkMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (isOwner && !isMe) SecondaryButton("Remove", { removing = p }, compact = true)
                        }
                    }
                    item(key = "leave") {
                        HorizontalDivider(color = c.line)
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                            SecondaryButton("Leave group", { leaving = true }, Modifier.fillMaxWidth(), icon = Lucide.LogOut, danger = true)
                            if (isOwner && info.members.size > 1) {
                                Text(
                                    "If you leave, the longest-standing member becomes the owner.",
                                    color = c.inkMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
                if (renaming) {
                    RenameDialog(info.title, onDismiss = { renaming = false }) { title ->
                        run { Chat.rename(conversationId, title); loader.reload(silent = true) }
                    }
                }
                removing?.let { p ->
                    ConfirmDialog(
                        "Remove ${p.fullName}?",
                        "They'll leave the group and stop getting its messages.",
                        "Remove",
                        onConfirm = { run { Chat.removeMember(conversationId, p.id); loader.reload(silent = true) } },
                        onDismiss = { removing = null },
                    )
                }
                if (leaving) {
                    ConfirmDialog(
                        "Leave this group?",
                        "You'll stop receiving its messages.",
                        "Leave",
                        onConfirm = {
                            run {
                                Chat.leave(conversationId)
                                app.refreshBadges()
                                nav.backTo(MessagesRoute)
                            }
                        },
                        onDismiss = { leaving = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Dheu.colors
    var draft by remember { mutableStateOf(current) }
    val ok = draft.isNotBlank() && draft.trim() != current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Rename group", style = MaterialTheme.typography.titleLarge) },
        text = { DheuTextField(draft, { draft = it }, "Group name", maxLength = GROUP_TITLE_MAX) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onSave(draft.trim()) }, enabled = ok) {
                Text("Save", color = if (ok) c.brand else c.inkFaint, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.inkMuted) } },
    )
}

/** Tick people from a searchable list; refuses more than [limit]. */
@Composable
private fun PeoplePicker(
    people: List<Person>,
    selected: Set<String>,
    limit: Int,
    onChange: (Set<String>) -> Unit,
    onLimit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Dheu.colors
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) people else people.filter { it.username.contains(q) || it.fullName.lowercase().contains(q) }

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search people") },
            leadingIcon = { Icon(Lucide.Search, null, tint = c.inkFaint) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        if (shown.isEmpty()) {
            Text(if (people.isEmpty()) "There's no one else to add." else "No one matches that.", color = c.inkMuted, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.id }) { p ->
                val on = p.id in selected
                Row(
                    Modifier.fillMaxWidth().clickable {
                        when {
                            on -> onChange(selected - p.id)
                            selected.size >= limit -> onLimit("You can add $limit more ${if (limit == 1) "person" else "people"} at most.")
                            else -> onChange(selected + p.id)
                        }
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(p.avatarPath, p.fullName, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.username, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.fullName, color = c.inkMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .then(if (on) Modifier.background(c.brand) else Modifier.border(2.dp, c.line, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(Lucide.Check, "Selected", tint = c.brandInk, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PickerFooter(label: String, enabled: Boolean, busy: Boolean, error: String?, onClick: () -> Unit) {
    val c = Dheu.colors
    Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = c.line)
        if (error != null) Text(error, color = c.danger, style = MaterialTheme.typography.bodySmall)
        PrimaryButton(label, onClick, Modifier.fillMaxWidth(), enabled = enabled, loading = busy)
    }
}
