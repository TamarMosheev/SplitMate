package com.example.myapplication.data.api

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds "Authorization: Bearer <Firebase ID token>" to every request.
 * getIdToken(false) returns the cached token and refreshes it automatically when expired.
 * On a 401 the token is force-refreshed and the request is retried once.
 * Runs on OkHttp's background thread, so blocking on the Task is safe here.
 */
class AuthInterceptor(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request().withToken(fetchToken(forceRefresh = false)))
        if (response.code != 401) return response

        response.close()
        return chain.proceed(chain.request().withToken(fetchToken(forceRefresh = true)))
    }

    private fun okhttp3.Request.withToken(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun fetchToken(forceRefresh: Boolean): String {
        val user = firebaseAuth.currentUser ?: throw IOException("NOT_SIGNED_IN")
        return try {
            Tasks.await(user.getIdToken(forceRefresh), TOKEN_TIMEOUT_SEC, TimeUnit.SECONDS).token
                ?: throw IOException("NO_TOKEN")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("TOKEN_FAILED", e)
        }
    }

    private companion object {
        const val TOKEN_TIMEOUT_SEC = 15L
    }
}
