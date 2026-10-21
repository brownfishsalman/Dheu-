package com.salmansaadiq.dheu.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.Relationships
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PeopleList
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private class FollowList(val visible: Boolean, val people: List<Person>, val relationships: Relationships?)

@Composable
fun FollowListScreen(profileId: String, username: String, followers: Boolean) {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val loader = rememberLoader("follows-$profileId-$followers") {
        // Same rule as the website: lists of a private account are for approved followers.
        if (!Profiles.canViewContentOf(profileId)) return@rememberLoader FollowList(false, emptyList(), null)
        coroutineScope {
            val rel = async { Profiles.relationships(me) }
            val people = if (followers) Profiles.followers(profileId) else Profiles.following(profileId)
            FollowList(true, people, rel.await())
        }
    }
    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar(if (followers) "Followers" else "Following", subtitle = "@$username", onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready ->
                if (!s.value.visible) ErrorState("This account is private.", onRetry = null)
                else PeopleList(s.value.people, s.value.relationships, if (followers) "No followers yet." else "Not following anyone yet.")
        }
    }
}
