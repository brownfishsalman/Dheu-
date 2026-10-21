package com.salmansaadiq.dheu.data

import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.Instant

fun nowIso(): String = Instant.now().toString()

@Serializable
data class IdRow(val id: String)

@Serializable
data class PersonRow(val profile: Person? = null)

/** The error code Postgres gave, e.g. "23505" for a duplicate. */
val Throwable.pgCode: String? get() = (this as? PostgrestRestException)?.code

/** A sentence a member can read, for any failure the backend throws. */
fun Throwable.friendly(fallback: String = "Something went wrong. Please try again."): String = when (this) {
    is HttpRequestException, is HttpRequestTimeoutException, is IOException -> "Couldn't connect. Check your internet and try again."
    is AuthRestException -> when {
        errorDescription.contains("banned", ignoreCase = true) -> "This account has been suspended."
        errorDescription.contains("invalid login", ignoreCase = true) -> "Wrong email or password."
        else -> errorDescription
    }
    is PostgrestRestException -> fallback
    is RestException -> description ?: fallback
    is IllegalArgumentException -> message ?: fallback
    else -> fallback
}

/** Upload to Storage with a long browser/CDN cache: paths are unique and never rewritten. */
suspend fun uploadFile(bucket: String, path: String, bytes: ByteArray, contentType: ContentType, cacheSeconds: Int) {
    Backend.storage.from(bucket).upload(path, bytes) {
        upsert = false
        this.contentType = contentType
        httpOverride { headers.append(HttpHeaders.CacheControl, "max-age=$cacheSeconds") }
    }
}

/** Best-effort file cleanup: a stray file is cheaper than a failed delete. */
suspend fun removeFiles(bucket: String, paths: List<String>) {
    if (paths.isEmpty()) return
    runCatching { Backend.storage.from(bucket).delete(paths) }
}

const val CACHE_YEAR = 31_536_000
const val CACHE_WEEK = 604_800
const val CACHE_DAY = 86_400

class Page<T>(val items: List<T>, val nextCursor: String?)

@Serializable
data class FollowingId(@SerialName("following_id") val followingId: String)

@Serializable
data class TargetId(@SerialName("target_id") val targetId: String)
