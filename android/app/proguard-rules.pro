# R8 keep rules for the release build.
# kotlinx.serialization, Ktor, OkHttp, Coil and supabase-kt ship their own
# consumer rules. The HTTP engine and Coil's network fetcher are wired up by
# hand (data/Backend.kt, DheuApp.kt), so nothing relies on ServiceLoader.

# Readable stack traces from testers: keep line numbers, hide source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
