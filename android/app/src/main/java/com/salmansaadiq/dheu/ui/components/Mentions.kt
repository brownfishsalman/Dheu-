package com.salmansaadiq.dheu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.salmansaadiq.dheu.data.Mentions
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.delay

private const val SUGGESTIONS = 6

/**
 * Text with every @username as a link to that profile. [prefix] (a bold
 * username, say) goes in front and [suffix] (" more") after it, muted;
 * [highlight] marks mentions of that username (the viewer, in chat bubbles).
 */
@Composable
fun MentionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Dheu.colors.ink,
    mentionColor: Color = Dheu.colors.brand,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    prefix: String? = null,
    suffix: String? = null,
    highlight: String? = null,
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val nav = LocalNav.current
    val c = Dheu.colors
    val annotated = remember(text, prefix, suffix, highlight, mentionColor, c.brand) {
        buildAnnotatedString {
            if (prefix != null) {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                append(prefix)
                pop()
                append("  ")
            }
            for (part in Mentions.split(text)) {
                val username = part.username
                if (username == null) {
                    append(part.text)
                } else {
                    val mine = username == highlight
                    val span = SpanStyle(
                        color = mentionColor,
                        fontWeight = FontWeight.SemiBold,
                        background = if (mine) c.brand.copy(alpha = 0.15f) else Color.Unspecified,
                    )
                    withLink(LinkAnnotation.Clickable(username, TextLinkStyles(span)) { nav.profile(username) }) { append(part.text) }
                }
            }
            if (suffix != null) {
                pushStyle(SpanStyle(color = c.inkMuted))
                append(suffix)
                pop()
            }
        }
    }
    Text(annotated, modifier = modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = onTextLayout)
}

/**
 * People to mention while an @word is being typed into [value]: from [people]
 * when given (a group's members), otherwise everyone on Dheu from the first
 * letter. Picking one replaces the @word and puts the caret after it.
 */
@Composable
fun MentionSuggestions(value: TextFieldValue, onPick: (TextFieldValue) -> Unit, modifier: Modifier = Modifier, people: List<Person>? = null) {
    val c = Dheu.colors
    val at = if (value.selection.collapsed) Mentions.queryAt(value.text, value.selection.start) else null
    val query = at?.second
    var found by remember { mutableStateOf<List<Person>>(emptyList()) }

    LaunchedEffect(query, people) {
        if (query.isNullOrEmpty() || people != null) return@LaunchedEffect
        delay(150)
        found = runCatching { Profiles.search(query, SUGGESTIONS.toLong()) }.getOrDefault(emptyList())
    }

    val shown = when {
        query == null -> emptyList()
        people != null -> people.filter { it.username.startsWith(query) || it.fullName.lowercase().contains(query) }.take(SUGGESTIONS)
        query.isEmpty() -> emptyList()
        else -> found.filter { it.username.contains(query) || it.fullName.lowercase().contains(query) }
    }
    if (at == null || shown.isEmpty()) return

    Column(modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(color = c.line)
        shown.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    val (text, caret) = Mentions.insert(value.text, at.first, value.selection.start, p.username)
                    onPick(TextFieldValue(text, TextRange(caret)))
                }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p.avatarPath, p.fullName, 32.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.username, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(p.fullName, style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 1)
                }
            }
        }
    }
}
