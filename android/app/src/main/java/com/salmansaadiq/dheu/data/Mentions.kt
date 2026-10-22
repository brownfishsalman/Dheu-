package com.salmansaadiq.dheu.data

/**
 * @mentions in captions, comments and group messages. The website
 * (src/lib/mentions.ts) and the database (mentioned_profiles() in
 * 0020_mentions_app_push.sql) read them with the same rule: an @ at the start
 * or after a character that can't be part of a username, then 3–30 of
 * [a-z0-9_.]. A trailing full stop ends the sentence, not the name.
 */
object Mentions {
    private val MENTION = Regex("(^|[^A-Za-z0-9_.@])@([A-Za-z0-9_.]{3,30})")
    private val TYPING = Regex("(^|[^A-Za-z0-9_.@])@([A-Za-z0-9_.]{0,30})$")

    /** A run of plain text, or a mention (then [username] is set and [text] is "@name"). */
    data class Part(val text: String, val username: String? = null)

    fun split(text: String): List<Part> {
        val parts = mutableListOf<Part>()
        var last = 0
        for (m in MENTION.findAll(text)) {
            val name = m.groupValues[2].trimEnd('.')
            if (name.length < 3) continue
            val at = m.range.first + m.groupValues[1].length
            if (at > last) parts += Part(text.substring(last, at))
            parts += Part("@$name", name.lowercase())
            last = at + 1 + name.length
        }
        if (last < text.length) parts += Part(text.substring(last))
        return parts
    }

    /** The @word being typed just before the caret: where its @ is, and what follows it. */
    fun queryAt(text: String, caret: Int): Pair<Int, String>? {
        val m = TYPING.find(text.substring(0, caret.coerceIn(0, text.length))) ?: return null
        val query = m.groupValues[2]
        return (caret - query.length - 1) to query.lowercase()
    }

    /** Replace the @word being typed with "@username " and return the new text and caret. */
    fun insert(text: String, start: Int, caret: Int, username: String): Pair<String, Int> {
        val inserted = "@$username "
        val rest = text.substring(caret).replace(Regex("^[A-Za-z0-9_.]*"), "").removePrefix(" ")
        return (text.substring(0, start) + inserted + rest) to (start + inserted.length)
    }
}
