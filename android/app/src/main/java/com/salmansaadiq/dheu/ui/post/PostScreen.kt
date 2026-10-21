package com.salmansaadiq.dheu.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salmansaadiq.dheu.core.timeAgo
import com.salmansaadiq.dheu.data.Comment
import com.salmansaadiq.dheu.data.Post
import com.salmansaadiq.dheu.data.Posts
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PeopleList
import com.salmansaadiq.dheu.ui.components.fieldColors
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private class PostWithComments(val post: Post?, val comments: List<Comment>)

@Composable
fun PostScreen(id: String) {
    val nav = LocalNav.current
    val loader = rememberLoader("post-$id") {
        coroutineScope {
            val comments = async { Posts.comments(id) } // RLS only returns them if the post is visible
            PostWithComments(Posts.byId(id), comments.await())
        }
    }
    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        val state = loader.state
        val author = (state as? Load.Ready)?.value?.post?.author
        DheuTopBar("Post", subtitle = author?.let { "@${it.username}" }, onBack = nav::back)
        when (state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(state.message, onRetry = { loader.reload() })
            is Load.Ready -> {
                val post = state.value.post
                if (post == null) ErrorState("This post isn't available. It may have expired or be private.", onRetry = null)
                else PostWithCommentsView(post, state.value.comments, onDeleted = nav::back)
            }
        }
    }
}

@Composable
private fun PostWithCommentsView(post: Post, initial: List<Comment>, onDeleted: () -> Unit) {
    val app = LocalAppState.current
    val me by app.me.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var comments by remember(post.id) { mutableStateOf(initial) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Comment?>(null) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f)) {
            item(key = "post") { PostCard(post, onDeleted = onDeleted, detail = true) }
            if (comments.isEmpty()) {
                item(key = "none") { Text("No comments yet. Say something nice.", color = c.inkMuted, modifier = Modifier.padding(24.dp)) }
            }
            items(comments, key = { it.id }) { comment ->
                val author = comment.author ?: return@items
                val canDelete = comment.authorId == app.userId || post.authorId == app.userId || me?.isAdmin == true
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Avatar(author.avatarPath, author.fullName, 32.dp, Modifier.clickable { nav.profile(author.username) })
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(author.username) }
                            append("  ")
                            append(comment.body)
                        })
                        Text(timeAgo(comment.createdAt), color = c.inkFaint, style = MaterialTheme.typography.bodySmall)
                    }
                    if (canDelete) {
                        IconButton(onClick = { deleting = comment }, modifier = Modifier.size(32.dp)) {
                            Icon(Lucide.Trash, "Delete comment", tint = c.inkFaint, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            me?.let { Avatar(it.avatarPath, it.fullName, 30.dp) }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 1000) draft = it },
                placeholder = { Text("Add a comment…") },
                shape = RoundedCornerShape(24.dp),
                colors = fieldColors(),
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = draft.isNotBlank() && !sending,
                onClick = {
                    val author = me?.person ?: return@IconButton
                    sending = true
                    scope.launch {
                        try {
                            comments = comments + Posts.addComment(post.id, author, draft.trim())
                            draft = ""
                        } catch (e: Exception) {
                            toast(e.friendly("Couldn't post your comment."))
                        } finally {
                            sending = false
                        }
                    }
                },
            ) { Icon(Lucide.Send, "Post comment", tint = if (draft.isNotBlank()) c.brand else c.inkFaint) }
        }
    }

    deleting?.let { target ->
        ConfirmDialog(
            title = "Delete this comment?",
            text = "It will be removed for everyone.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    try {
                        Posts.deleteComment(target.id)
                        comments = comments.filter { it.id != target.id }
                    } catch (e: Exception) {
                        toast(e.friendly("Couldn't delete the comment."))
                    }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
fun LikesScreen(postId: String) {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val loader = rememberLoader("likes-$postId") {
        coroutineScope {
            val rel = async { Profiles.relationships(me) }
            Posts.likers(postId) to rel.await()
        }
    }
    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar("Likes", onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready -> PeopleList(s.value.first, s.value.second, "No likes yet.")
        }
    }
}
