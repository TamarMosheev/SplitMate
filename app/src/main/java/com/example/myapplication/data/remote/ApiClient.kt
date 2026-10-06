package com.example.myapplication.data.remote

import android.util.Log
import com.example.myapplication.BuildConfig
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Supplies the signed-in user's Firebase ID token. Called on OkHttp's background threads (it may block). */
fun interface TokenProvider {
    fun idToken(forceRefresh: Boolean): String?
}

/** The real provider: the current Firebase user's ID token (the SDK caches it and refreshes it when it expires). */
object FirebaseTokenProvider : TokenProvider {
    override fun idToken(forceRefresh: Boolean): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return try {
            Tasks.await(user.getIdToken(forceRefresh), 10, TimeUnit.SECONDS).token
        } catch (e: Exception) {
            Log.w(TAG, "Could not get a Firebase ID token (forceRefresh=$forceRefresh)", e)
            null
        }
    }

    private const val TAG = "FirebaseTokenProvider"
}

/** Builds the Retrofit client for the SplitMate backend. */
object ApiClient {

    /** The app-wide client, pointed at [BuildConfig.API_BASE_URL]. */
    val api: SplitMateApi by lazy { create(BuildConfig.API_BASE_URL, FirebaseTokenProvider) }

    fun create(baseUrl: String, tokenProvider: TokenProvider): SplitMateApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // Every request carries the current ID token.
            .addInterceptor { chain ->
                val token = tokenProvider.idToken(false)
                val request = chain.request().newBuilder().apply {
                    if (token != null) header("Authorization", "Bearer $token")
                }.build()
                chain.proceed(request)
            }
            // On a 401, force-refresh the token and retry the request ONCE (the contract's recommended flow).
            // priorResponse != null means this request was already a retry: give up instead of looping.
            .authenticator { _, response ->
                if (response.priorResponse != null) {
                    null
                } else {
                    tokenProvider.idToken(true)?.let { fresh ->
                        response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
                    }
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SplitMateApi::class.java)
    }
}
