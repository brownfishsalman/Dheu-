package com.salmansaadiq.dheu.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Relationships
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.theme.Dheu

/** A member in a list: avatar, username, name, and (for anyone but me) a follow button. */
@Composable
fun PersonRow(person: Person, relationships: Relationships?, trailing: (@Composable () -> Unit)? = null) {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    Row(
        Modifier.fillMaxWidth().clickable { nav.profile(person.username) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(person.avatarPath, person.fullName, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.username, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(person.fullName, color = Dheu.colors.inkMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            trailing != null -> trailing()
            person.id != me && relationships != null -> FollowButton(person.id, relationships.stateOf(person.id))
        }
    }
}

@Composable
fun PeopleList(people: List<Person>, relationships: Relationships?, emptyText: String, modifier: Modifier = Modifier) {
    if (people.isEmpty()) {
        Text(emptyText, color = Dheu.colors.inkMuted, modifier = Modifier.fillMaxWidth().padding(32.dp))
        return
    }
    LazyColumn(modifier) {
        items(people, key = { it.id }) { person ->
            PersonRow(person, relationships)
            HorizontalDivider(color = Dheu.colors.line.copy(alpha = 0.6f))
        }
    }
}
