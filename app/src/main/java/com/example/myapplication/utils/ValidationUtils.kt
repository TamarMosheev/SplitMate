package com.example.myapplication.utils

import android.util.Patterns

object ValidationUtils {

    fun validateEmail(email: String): String? {
        if (email.isBlank()) {
            return "נא להזין כתובת אימייל"
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return "כתובת אימייל אינה תקינה"
        }
        return null // תקין לחלוטין
    }

    const val WEAK_NEW_PASSWORD = "הסיסמה חייבת להכיל לפחות 8 תווים, אות אחת ומספר אחד"

    /** Password-reset policy (same as the backend): 8+ characters, at least one letter and one digit. */
    fun validateNewPassword(password: String): String? = when {
        password.isEmpty() -> "נא להזין סיסמה"
        password.length < 8 || password.none { it.isLetter() } || password.none { it.isDigit() } -> WEAK_NEW_PASSWORD
        else -> null
    }

    fun validatePassword(password: String): String? {
        if (password.isBlank()) {
            return "נא להזין סיסמה"
        }
        if (password.length < 6) {
            return "הסיסמה חייבת להכיל לפחות 6 תווים"
        }
        return null // תקין לחלוטין
    }
}