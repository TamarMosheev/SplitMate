package com.example.myapplication.repository

import com.example.myapplication.data.api.ApiErrorMapper
import com.example.myapplication.data.api.PasswordResetApi
import com.example.myapplication.data.api.PasswordResetCompleteBody
import com.example.myapplication.data.api.PasswordResetEmailBody
import com.example.myapplication.data.api.PasswordResetVerifyBody
import com.example.myapplication.data.api.RetrofitClient
import com.example.myapplication.utils.Resource
import com.example.myapplication.utils.ValidationUtils
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * Backend password reset: request -> verify (returns a one-time resetToken) -> complete.
 * Nothing here is stored or logged: the code, resetToken and password only live in memory for the flow.
 */
class PasswordResetRepository(
    private val api: PasswordResetApi = RetrofitClient.passwordResetApi
) {

    private enum class Step { REQUEST, VERIFY, COMPLETE }

    suspend fun requestCode(email: String): Resource<Unit> =
        call(Step.REQUEST) { api.request(PasswordResetEmailBody(email)) }.toUnit()

    suspend fun resendCode(email: String): Resource<Unit> =
        call(Step.REQUEST) { api.resend(PasswordResetEmailBody(email)) }.toUnit()

    /** On success returns the resetToken (only when the server says `verified == true`). */
    suspend fun verifyCode(email: String, code: String): Resource<String> =
        when (val r = call(Step.VERIFY) { api.verify(PasswordResetVerifyBody(email, code)) }) {
            is Resource.Success ->
                r.data.resetToken?.takeIf { r.data.verified && it.isNotBlank() }
                    ?.let { Resource.Success(it) } ?: Resource.Error(WRONG_CODE)
            is Resource.Error -> r
            Resource.Loading -> Resource.Loading
        }

    suspend fun completeReset(resetToken: String, newPassword: String): Resource<Unit> =
        when (val r = call(Step.COMPLETE) { api.complete(PasswordResetCompleteBody(resetToken, newPassword)) }) {
            is Resource.Success -> if (r.data.success) Resource.Success(Unit) else Resource.Error(GENERIC)
            is Resource.Error -> r
            Resource.Loading -> Resource.Loading
        }

    private fun <T> Resource<T>.toUnit(): Resource<Unit> = when (this) {
        is Resource.Success -> Resource.Success(Unit)
        is Resource.Error -> this
        Resource.Loading -> Resource.Loading
    }

    private suspend fun <T> call(step: Step, block: suspend () -> T): Resource<T> =
        try {
            Resource.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Resource.Error(httpMessage(step, e))
        } catch (e: IOException) {
            Resource.Error(NETWORK)
        } catch (e: Exception) {
            Resource.Error(GENERIC)
        }

    /** Maps status + the server's `detail` to Hebrew; the raw backend text is never shown. */
    private fun httpMessage(step: Step, e: HttpException): String {
        val code = e.code()
        val detail = ApiErrorMapper.detail(e).orEmpty().lowercase()
        return when (step) {
            Step.REQUEST -> when {
                code == 429 -> "נשלחו יותר מדי בקשות. נסו שוב בעוד דקה"
                "google" in detail -> "החשבון הזה מחובר באמצעות Google. התחברו עם Google"
                "email" in detail -> "כתובת האימייל אינה תקינה"
                else -> "לא ניתן לשלוח קוד כרגע. נסו שוב"
            }
            Step.VERIFY -> when {
                code == 429 || "too many" in detail || "attempt" in detail -> "ניסית יותר מדי פעמים. בקשו קוד חדש"
                "expired" in detail && "invalid" !in detail -> "הקוד פג תוקף. בקשו קוד חדש"
                code == 400 || code == 401 -> WRONG_CODE
                else -> GENERIC
            }
            Step.COMPLETE -> when {
                code == 401 || "token" in detail -> "תהליך האיפוס פג תוקף. התחילו מחדש"
                code == 400 && "password" in detail -> ValidationUtils.WEAK_NEW_PASSWORD
                code == 429 -> "ניסית יותר מדי פעמים. נסו שוב מאוחר יותר"
                else -> GENERIC
            }
        }
    }

    private companion object {
        const val WRONG_CODE = "קוד האימות שגוי"
        const val NETWORK = "לא ניתן להתחבר לשרת"
        const val GENERIC = "משהו השתבש. נסו שוב"
    }
}
