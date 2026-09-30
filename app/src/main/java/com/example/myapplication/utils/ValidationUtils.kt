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