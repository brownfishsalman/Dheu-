package com.salmansaadiq.dheu.ui.messages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.salmansaadiq.dheu.R
import com.salmansaadiq.dheu.core.clock
import com.salmansaadiq.dheu.core.dayLabel
import com.salmansaadiq.dheu.data.Chat
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.EmptyState
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PersonRow
import com.salmansaadiq.dheu.ui.components.SecondaryButton
import com.salmansaadiq.dheu.ui.components.fieldColors
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.ChatRoute
import com.salmansaadiq.dheu.ui.nav.DirectMessageRoute
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.delay

/** "Message" on a profile: find or create the DM, then show it in place of this screen. */
@Composable
fun DirectMessageScreen(userId: String) {
    val nav = LocalNav.current
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) {
        try {
            nav.replace(ChatRoute(Chat.directConversation(userId)))
        } catch (e: Exception) {
            error = if (e.message.orEmpty().contains("blocked")) "You can't message this member." else e.friendly("Couldn't open the chat.")
        }
    }
    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar("Messages", onBack = nav::back)
        val shown = error
        if (shown != null) ErrorState(shown, onRetry = null) else Loading()
    }
}

@Composable
fun NewMessageScreen() {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val c = Dheu.colors
    var query by rememberSaveable { mutableStateOf("") }
    var people by remember { mutableStateOf<List<Person>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        delay(if (query.isEmpty()) 0 else 300)
        try {
            people = Profiles.search(query).filter { it.id != me }
            error = null
        } catch (e: Exception) {
            error = e.friendly()
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        DheuTopBar("New message", onBack = nav::back)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search people") },
            leadingIcon = { Icon(Lucide.Search, null, tint = c.inkFaint) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Text(
            "New groups and adding people are on the website for now.",
            color = c.inkFaint,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        when {
            error != null && people == null -> ErrorState(error!!, onRetry = null)
            people == null -> Loading()
            people!!.isEmpty() -> Text("No one matches that.", color = c.inkMuted, modifier = Modifier.padding(24.dp))
            else -> LazyColumn {
                items(people!!, key = { it.id }) { person ->
                    PersonRow(person, relationships = null, trailing = {
                        SecondaryButton("Message", { nav.replace(DirectMessageRoute(person.id)) }, compact = true)
                    })
                }
            }
        }
    }
}

/** The admin's read-only thread pinned in every inbox. */
@Composable
fun AnnouncementsScreen() {
    val nav = LocalNav.current
    val app = LocalAppState.current
    val c = Dheu.colors
    val loader = rememberLoader("announcements") { Chat.announcements() }
    LaunchedEffect(loader.state is Load.Ready) {
        if (loader.state is Load.Ready) {
            runCatching { Chat.markAnnouncementsRead() }
            app.refreshBadges()
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        DheuTopBar("Dheu announcements", subtitle = "From the admin", onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready ->
                if (s.value.isEmpty()) {
                    EmptyState("Nothing yet", "News and new features will appear here.")
                } else {
                    LazyColumn(Modifier.weight(1f), reverseLayout = true) {
                        items(s.value.asReversed(), key = { it.id }) { a ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text("${dayLabel(a.createdAt)} · ${clock(a.createdAt)}", color = c.inkFaint, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Box(Modifier.size(28.dp).clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                                        Image(painterResource(R.drawable.dheu_mark), null, Modifier.height(16.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        a.body,
                                        modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
        }
        Text(
            "Only the admin can post here.",
            color = c.inkFaint,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
        )
    }
}
