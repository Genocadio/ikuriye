package com.gocavgo.ikuriye.supa

import com.gocavgo.ikuriye.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object SupaClient {

    @OptIn(SupabaseInternal::class)
    val instance: SupabaseClient by lazy { createSupabaseClient() }

    @OptIn(SupabaseInternal::class)
    fun createSupabaseClient(
        supabaseUrl: String = BuildConfig.SUPABASE_URL,
        supabaseKey: String = BuildConfig.SUPABASE_KEY,
        sessionManager: SessionManager? = null
    ): SupabaseClient {
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
            install(Auth) {
                autoSetupPlatform = false
                // Proactively refresh the token before expiry so GraphQL requests
                // never encounter a 401. The SDK internally checks expiry and
                // calls refreshCurrentSession() when the token is near expiration.
                alwaysAutoRefresh = true

                // Delegate session persistence to our custom SessionManager
                if (sessionManager != null) {
                    this.sessionManager = sessionManager
                }
                autoSaveToStorage = true
                autoLoadFromStorage = true

                // Provide a scope for the SDK's internal async operations
                authScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            }
            install(Storage)
            install(Postgrest)
            install(Realtime)
        }
    }
}
