package com.example.myapplication.data.api

import retrofit2.http.Body
import retrofit2.http.POST

data class PasswordResetEmailBody(val email: String)
data class PasswordResetVerifyBody(val email: String, val code: String)
data class PasswordResetCompleteBody(val resetToken: String, val newPassword: String)

data class PasswordResetRequestResult(val success: Boolean = false, val message: String? = null)
data class PasswordResetVerifyResult(val verified: Boolean = false, val resetToken: String? = null)
data class PasswordResetCompleteResult(val success: Boolean = false)

/** Unauthenticated endpoints (the user is signed out while resetting). Never log bodies: they hold codes/tokens. */
interface PasswordResetApi {

    @POST("auth/password-reset/request")
    suspend fun request(@Body body: PasswordResetEmailBody): PasswordResetRequestResult

    /** Replaces the previous code; the server enforces a 60 s cooldown (429). */
    @POST("auth/password-reset/resend")
    suspend fun resend(@Body body: PasswordResetEmailBody): PasswordResetRequestResult

    @POST("auth/password-reset/verify")
    suspend fun verify(@Body body: PasswordResetVerifyBody): PasswordResetVerifyResult

    @POST("auth/password-reset/complete")
    suspend fun complete(@Body body: PasswordResetCompleteBody): PasswordResetCompleteResult
}
