package com.salmansaadiq.dheu.ui.post

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.salmansaadiq.dheu.BuildConfig
import com.salmansaadiq.dheu.core.expiresIn
import com.salmansaadiq.dheu.core.timeAgo
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.data.Buckets
import com.salmansaadiq.dheu.data.Post
import com.salmansaadiq.dheu.data.PostImage
import com.salmansaadiq.dheu.data.Posts
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.DheuSheet
import com.salmansaadiq.dheu.ui.components.SheetAction
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.LikesRoute
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One post: header, swipeable photos (double-tap to like), actions, caption. */
@Composable
fun PostCard(post: Post, onDeleted: () -> Unit, detail: Boolean = false) {
    val c = Dheu.colors
    val nav = LocalNav.current
    val app = LocalAppState.current
    val me = app.userId
    val myProfile by app.me.collectAsStateWithLifecycle()
    val isAdmin = myProfile?.isAdmin == true
    val author = post.author ?: return
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val context = LocalContext.current

    var liked by remember(post.id) { mutableStateOf(post.likedByMe) }
    var likes by remember(post.id) { mutableIntStateOf(post.likeCount) }
    var burst by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(detail) }

    fun setLiked(next: Boolean) {
        if (next == liked) return
        liked = next
        likes += if (next) 1 else -1
        scope.launch {
            try {
                if (next) Posts.like(post.id, me) else Posts.unlike(post.id, me)
            } catch (e: Exception) {
                liked = !next
                likes += if (next) -1 else 1
                toast(e.friendly())
            }
        }
    }

    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable { nav.profile(author.username) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(author.avatarPath, author.fullName, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(author.username, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    Text(timeAgo(post.createdAt), color = c.inkFaint, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = { menu = true }) { Icon(Lucide.Ellipsis, "Post options", tint = c.inkMuted) }
        }

        Box {
            Carousel(post.images, onDoubleTap = {
                burst = true
                setLiked(true)
            })
            HeartBurst(burst, Modifier.align(Alignment.Center))
            LaunchedEffect(burst) {
                if (burst) {
                    delay(700)
                    burst = false
                }
            }
        }

        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { setLiked(!liked) }) {
                Icon(
                    if (liked) Lucide.HeartFilled else Lucide.Heart,
                    if (liked) "Unlike" else "Like",
                    tint = if (liked) c.like else c.ink,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = { if (!detail) nav.post(post.id) }) {
                Icon(Lucide.MessageCircle, "Comments", tint = c.ink, modifier = Modifier.size(26.dp))
            }
        }

        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (likes > 0) {
                Text(
                    "$likes ${if (likes == 1) "like" else "likes"}",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { nav.to(LikesRoute(post.id)) },
                )
            }
            if (post.caption.isNotBlank()) {
                val long = post.caption.length > 140
                val shown = if (!expanded && long) post.caption.take(140).trimEnd() + "…" else post.caption
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(author.username) }
                        append("  ")
                        append(shown)
                        if (long && !expanded) withStyle(SpanStyle(color = c.inkMuted)) { append(" more") }
                    },
                    modifier = if (long && !expanded) Modifier.clickable { expanded = true } else Modifier,
                )
            }
            if (!detail && post.commentCount > 0) {
                Text(
                    if (post.commentCount == 1) "View 1 comment" else "View all ${post.commentCount} comments",
                    color = c.inkMuted,
                    modifier = Modifier.clickable { nav.post(post.id) },
                )
            }
        }
        HorizontalDivider(color = c.line)
    }

    if (menu) {
        DheuSheet("Post", onDismiss = { menu = false }) {
            SheetAction(Lucide.Link, "Copy link", onClick = {
                menu = false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Dheu post", "${BuildConfig.SITE_URL}/p/${post.id}"))
                toast("Link copied")
            })
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Clock, null, tint = c.inkMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(expiresIn(post.expiresAt), color = c.inkMuted)
            }
            if (post.authorId == me || isAdmin) {
                SheetAction(Lucide.Trash, "Delete post", onClick = { menu = false; confirmDelete = true }, danger = true)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this post?",
            text = "This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    try {
                        Posts.delete(post)
                        onDeleted()
                    } catch (e: Exception) {
                        toast(e.friendly("Couldn't delete the post."))
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** The white heart that pops over a photo on double-tap. */
@Composable
private fun HeartBurst(visible: Boolean, modifier: Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = scaleIn() + fadeIn(), exit = fadeOut()) {
        Icon(Lucide.HeartFilled, null, tint = Color.White, modifier = Modifier.size(96.dp))
    }
}

/** The frame takes the first photo's shape (no taller than 1:2); others letterbox inside it. */
@Composable
fun Carousel(images: List<PostImage>, onDoubleTap: () -> Unit) {
    val first = images.firstOrNull() ?: return
    val ratio = (first.width.toFloat() / first.height).coerceAtLeast(0.5f)
    val pager = rememberPagerState { images.size }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = Backend.publicUrl(Buckets.POSTS, images[page].path),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) },
            )
        }
        if (images.size > 1) {
            Text(
                "${pager.currentPage + 1}/${images.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(images.size) { i ->
                    Box(
                        Modifier.size(6.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.4f)),
                    )
                }
            }
        }
    }
}
