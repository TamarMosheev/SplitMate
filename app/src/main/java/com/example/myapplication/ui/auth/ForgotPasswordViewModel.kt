package com.example.myapplication.ui.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.PasswordResetRepository
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ForgotPasswordEvent {
    data class CodeSent(val email: String) : ForgotPasswordEvent()
    data class Message(val text: String) : ForgotPasswordEvent()
}

/** Step 1: POST /auth/password-reset/request. */
class ForgotPasswordViewModel(
    private val repository: PasswordResetRepository = PasswordResetRepository()
) : ViewModel() {

    private val _emailError = MutableStateFlow<String?>(null)
    val emailError: StateFlow<String?> = _emailError.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<ForgotPasswordEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ForgotPasswordEvent> = _events.asSharedFlow()

    fun sendCode(email: String) {
        if (_busy.value) return
        val trimmed = email.trim()
        val error = when {
            trimmed.isEmpty() -> "יש להזין כתובת אימייל"
            !Patterns.EMAIL_ADDRESS.matcher(trimmed).matches() -> "כתובת האימייל אינה תקינה"
            else -> null
        }
        _emailError.value = error
        if (error != null) return

        _busy.value = true
        viewModelScope.launch {
            when (val r = repository.requestCode(trimmed)) {
                is Resource.Success -> _events.tryEmit(ForgotPasswordEvent.CodeSent(trimmed))
                is Resource.Error -> _events.tryEmit(ForgotPasswordEvent.Message(r.message))
                Resource.Loading -> Unit
            }
            _busy.value = false
        }
    }
}
