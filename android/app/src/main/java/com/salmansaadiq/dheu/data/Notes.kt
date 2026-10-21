package com.salmansaadiq.dheu.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Notes (0017): 60 characters, 24 hours, one per member, seen by mutual follows.
object Notes {
    private val client get() = Backend.client

    /** Mine and my mutual follows' live notes (RLS decides), newest first. */
    suspend fun visible(): List<Note> =
        client.from("notes").select(Columns.raw("body, created_at, expires_at, author:profiles!notes_author_id_fkey($PERSON)")) {
            order("created_at", Order.DESCENDING)
            limit(60)
        }.decodeList<Note>().filter { it.author != null }

    /** Shares a note, replacing any I had; the database stamps the 24 hours. */
    suspend fun set(body: String) {
        Backend.db.rpc("set_note", buildJsonObject { put("p_body", body) })
    }

    suspend fun delete(me: String) {
        client.from("notes").delete { filter { eq("author_id", me) } }
    }

    /** A reply arrives as a DM that keeps a copy of the note. Returns the conversation id. */
    suspend fun reply(note: Note, me: String, text: String): String {
        val author = requireNotNull(note.author)
        val conversationId = Chat.directConversation(author.id)
        Chat.insertMessage(MessageInsert(conversationId, me, text, noteText = note.body))
        return conversationId
    }
}
