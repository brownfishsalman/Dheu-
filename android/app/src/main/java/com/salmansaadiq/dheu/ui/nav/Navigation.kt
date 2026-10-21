package com.salmansaadiq.dheu.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.ui.AppState
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.activity.ActivityScreen
import com.salmansaadiq.dheu.ui.auth.AuthFlow
import com.salmansaadiq.dheu.ui.auth.ChoosePrivacyScreen
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.CountBadge
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.create.CreateScreen
import com.salmansaadiq.dheu.ui.feed.HomeScreen
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.messages.AnnouncementsScreen
import com.salmansaadiq.dheu.ui.messages.ChatScreen
import com.salmansaadiq.dheu.ui.messages.DirectMessageScreen
import com.salmansaadiq.dheu.ui.messages.InboxScreen
import com.salmansaadiq.dheu.ui.messages.NewMessageScreen
import com.salmansaadiq.dheu.ui.post.LikesScreen
import com.salmansaadiq.dheu.ui.post.PostScreen
import com.salmansaadiq.dheu.ui.profile.FollowListScreen
import com.salmansaadiq.dheu.ui.profile.ProfileScreen
import com.salmansaadiq.dheu.ui.search.SearchScreen
import com.salmansaadiq.dheu.ui.settings.BlockedScreen
import com.salmansaadiq.dheu.ui.settings.EditProfileScreen
import com.salmansaadiq.dheu.ui.settings.SettingsScreen
import com.salmansaadiq.dheu.ui.stories.StoryViewerScreen
import com.salmansaadiq.dheu.ui.theme.Dheu
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.serialization.Serializable

// ---- routes -------------------------------------------------------------------

@Serializable object HomeRoute
@Serializable object SearchRoute
@Serializable data class CreateRoute(val story: Boolean = false)
@Serializable object MessagesRoute
@Serializable object MeRoute
@Serializable data class ProfileRoute(val username: String)
@Serializable data class FollowListRoute(val profileId: String, val username: String, val followers: Boolean)
@Serializable data class PostRoute(val id: String)
@Serializable data class LikesRoute(val postId: String)
@Serializable data class StoryRoute(val authorId: String)
@Serializable object ActivityRoute
@Serializable data class ChatRoute(val conversationId: String)
@Serializable data class DirectMessageRoute(val userId: String)
@Serializable object NewMessageRoute
@Serializable object AnnouncementsRoute
@Serializable object SettingsRoute
@Serializable object EditProfileRoute
@Serializable object BlockedRoute

/** What screens use to move around, without holding the NavController themselves. */
class Nav(private val controller: NavHostController) {
    fun back() {
        controller.popBackStack()
    }

    fun to(route: Any) = controller.navigate(route)

    fun profile(username: String) = to(ProfileRoute(username))
    fun post(id: String) = to(PostRoute(id))
    fun story(authorId: String) = to(StoryRoute(authorId))
    fun chat(conversationId: String) = to(ChatRoute(conversationId))
    fun dm(userId: String) = to(DirectMessageRoute(userId))

    /** Swap the current screen for another (e.g. after posting, or once a DM is resolved). */
    fun replace(route: Any) {
        controller.popBackStack()
        controller.navigate(route)
    }

    /** Bottom-bar tabs keep their own back stacks. */
    fun tab(route: Any) = controller.navigate(route) {
        popUpTo(controller.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }

// ---- root -------------------------------------------------------------------

/** Signed out → log in / sign up; signed in → privacy choice if needed, then the app. */
@Composable
fun DheuRoot() {
    if (!Backend.configured) {
        ErrorState("This build has no Supabase settings. See android/README.md (local.properties).", onRetry = null)
        return
    }
    val status by Backend.auth.sessionStatus.collectAsStateWithLifecycle()
    when (val s = status) {
        is SessionStatus.Initializing -> Loading()
        is SessionStatus.NotAuthenticated -> AuthFlow()
        is SessionStatus.Authenticated -> {
            val id = s.session.user?.id ?: Backend.userId
            if (id != null) SignedIn(id) else AuthFlow()
        }
        // Token refresh failed (often offline): keep the member signed in; requests retry.
        is SessionStatus.RefreshFailure -> Backend.userId?.let { SignedIn(it) } ?: AuthFlow()
    }
}

@Composable
private fun SignedIn(userId: String) {
    val app: AppState = viewModel(key = "app-$userId") { AppState(userId) }
    val me by app.me.collectAsStateWithLifecycle()
    val meError by app.meError.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalAppState provides app) {
        val profile = me
        when {
            profile == null && meError != null -> ErrorState(meError!!, onRetry = app::refreshMe)
            profile == null -> Loading()
            profile.privacyChosenAt == null -> ChoosePrivacyScreen(onDone = app::refreshMe)
            else -> MainNav()
        }
    }
}

private data class Tab(val route: Any, val label: String, val icon: ImageVector?)

@Composable
private fun MainNav() {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
    val app = LocalAppState.current
    val me by app.me.collectAsStateWithLifecycle()
    val badges by app.badges.collectAsStateWithLifecycle()
    val entry by controller.currentBackStackEntryAsState()
    val destination = entry?.destination

    val tabs = listOf(
        Tab(HomeRoute, "Home", Lucide.Home),
        Tab(SearchRoute, "Search", Lucide.Search),
        Tab(CreateRoute(), "Create", Lucide.SquarePlus),
        Tab(MessagesRoute, "Messages", Lucide.MessageCircle),
        Tab(MeRoute, "Profile", null),
    )
    val showBar = tabs.any { destination?.hasRoute(it.route::class) == true }
    val c = Dheu.colors

    CompositionLocalProvider(LocalNav provides nav) {
        Scaffold(
            containerColor = c.canvas,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBar) {
                    Column {
                        HorizontalDivider(color = c.line)
                        NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
                            tabs.forEach { tab ->
                                val selected = destination?.hasRoute(tab.route::class) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { nav.tab(tab.route) },
                                    icon = {
                                        Box {
                                            if (tab.icon != null) {
                                                Icon(tab.icon, tab.label, modifier = Modifier.size(26.dp))
                                            } else {
                                                me?.let { Avatar(it.avatarPath, it.fullName, 26.dp) }
                                            }
                                            if (tab.route == MessagesRoute && badges.messages > 0) {
                                                Box(Modifier.align(Alignment.TopEnd).padding(start = 16.dp)) { CountBadge(badges.messages) }
                                            }
                                        }
                                    },
                                    label = null,
                                    alwaysShowLabel = false,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = c.ink,
                                        unselectedIconColor = c.inkMuted,
                                        indicatorColor = c.surface2,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(controller, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
                composable<HomeRoute> { HomeScreen() }
                composable<SearchRoute> { SearchScreen() }
                composable<CreateRoute> { CreateScreen(startWithStory = it.toRoute<CreateRoute>().story) }
                composable<MessagesRoute> { InboxScreen() }
                composable<MeRoute> { me?.let { profile -> ProfileScreen(profile.username, isTab = true) } }
                composable<ProfileRoute> { ProfileScreen(it.toRoute<ProfileRoute>().username) }
                composable<FollowListRoute> {
                    val r = it.toRoute<FollowListRoute>()
                    FollowListScreen(r.profileId, r.username, r.followers)
                }
                composable<PostRoute> { PostScreen(it.toRoute<PostRoute>().id) }
                composable<LikesRoute> { LikesScreen(it.toRoute<LikesRoute>().postId) }
                composable<StoryRoute> { StoryViewerScreen(it.toRoute<StoryRoute>().authorId) }
                composable<ActivityRoute> { ActivityScreen() }
                composable<ChatRoute> { ChatScreen(it.toRoute<ChatRoute>().conversationId) }
                composable<DirectMessageRoute> { DirectMessageScreen(it.toRoute<DirectMessageRoute>().userId) }
                composable<NewMessageRoute> { NewMessageScreen() }
                composable<AnnouncementsRoute> { AnnouncementsScreen() }
                composable<SettingsRoute> { SettingsScreen() }
                composable<EditProfileRoute> { EditProfileScreen() }
                composable<BlockedRoute> { BlockedScreen() }
            }
        }
    }
}
