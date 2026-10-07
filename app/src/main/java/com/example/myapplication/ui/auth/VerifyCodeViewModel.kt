package com.example.myapplication.ui.auth

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

sealed class VerifyCodeEvent {
    /** The server confirmed the code; [resetToken] goes to the New Password screen only (never persisted). */
    data class Verified(val resetToken: String) : VerifyCodeEvent()
    object Resent : VerifyCodeEvent()
    data class Message(val text: String) : VerifyCodeEvent()
}

/** Steps 2-3: POST /auth/password-reset/verify and /resend. */
class VerifyCodeViewModel(
    private val repository: PasswordResetRepository = PasswordResetRepository()
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<VerifyCodeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VerifyCodeEvent> = _events.asSharedFlow()

    fun verify(email: String, code: String) {
        if (_busy.value || code.length != CODE_LENGTH) return
        _busy.value = true
        viewModelScope.launch {
            when (val r = repository.verifyCode(email, code)) {
                is Resource.Success -> _events.tryEmit(VerifyCodeEvent.Verified(r.data))
                is Resource.Error -> _events.tryEmit(VerifyCodeEvent.Message(r.message))
                Resource.Loading -> Unit
            }
            _busy.value = false
        }
    }

    fun resend(email: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            when (val r = repository.resendCode(email)) {
                is Resource.Success -> _events.tryEmit(VerifyCodeEvent.Resent)
                is Resource.Error -> _events.tryEmit(VerifyCodeEvent.Message(r.message))
                Resource.Loading -> Unit
            }
            _busy.value = false
        }
    }

    companion object {
        const val CODE_LENGTH = 6
    }
}
