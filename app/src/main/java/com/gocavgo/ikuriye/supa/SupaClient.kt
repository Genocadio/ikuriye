package com.gocavgo.ikuriye.supa

import com.gocavgo.ikuriye.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/**
 * Supabase client — used for **file uploads only**. Auth (GoTrue), Postgrest
 * and Realtime are no longer installed: authentication is handled by Nexxauth,
 * notices come through GraphQL polling, and the backend owns the data.
 */
object SupaClient {

    /**
     * Fail fast with an actionable message instead of a cryptic connection error
     * when the project isn't configured. See `secrets.properties.example`.
     */
    private fun requireConfigured(supabaseUrl: String, supabaseKey: String) {
        check(supabaseUrl.isNotBlank()) {
            "SUPABASE_URL is empty. Copy secrets.properties.example to secrets.properties and set SUPABASE_URL, then rebuild."
        }
        check(supabaseKey.isNotBlank()) {
            "SUPABASE_KEY is empty. Copy secrets.properties.example to secrets.properties and set SUPABASE_KEY, then rebuild."
        }
    }

    @OptIn(SupabaseInternal::class)
    val instance: SupabaseClient by lazy { createSupabaseClient() }

    @OptIn(SupabaseInternal::class)
    fun createSupabaseClient(
        supabaseUrl: String = BuildConfig.SUPABASE_URL,
        supabaseKey: String = BuildConfig.SUPABASE_KEY
    ): SupabaseClient {
        requireConfigured(supabaseUrl, supabaseKey)
        return createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseKey
        ) {
            httpEngine = OkHttp.create {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(120, TimeUnit.SECONDS)
                    writeTimeout(120, TimeUnit.SECONDS)
                }
            }
            install(Storage)
        }
    }
}
