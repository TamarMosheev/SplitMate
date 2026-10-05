package com.example.myapplication.ui.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.GoogleAuthResult
import com.example.myapplication.data.model.User
import com.example.myapplication.repository.AuthRepository
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RegisterErrors(
    val nameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null
)

class RegisterViewModel(
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

    private val _errors = MutableStateFlow(RegisterErrors())
    val errors: StateFlow<RegisterErrors> = _errors.asStateFlow()

    private val _registerState = MutableStateFlow<Resource<User>?>(null)
    val registerState: StateFlow<Resource<User>?> = _registerState.asStateFlow()

    private val _googleState = MutableStateFlow<Resource<GoogleAuthResult>?>(null)
    val googleState: StateFlow<Resource<GoogleAuthResult>?> = _googleState.asStateFlow()

    private val _completeState = MutableStateFlow<Resource<Unit>?>(null)
    val completeState: StateFlow<Resource<Unit>?> = _completeState.asStateFlow()

    private val isBusy: Boolean
        get() = _registerState.value is Resource.Loading ||
            _googleState.value is Resource.Loading ||
            _completeState.value is Resource.Loading

    fun register(name: String, email: String, password: String) {
        if (isBusy) return

        val trimmedName = name.trim()
        val trimmedEmail = email.trim()
        val errors = RegisterErrors(
            nameError = if (trimmedName.isEmpty()) "יש להזין שם מלא" else null,
            emailError = when {
                trimmedEmail.isEmpty() -> "יש להזין כתובת אימייל"
                !Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches() -> "כתובת האימייל אינה תקינה"
                else -> null
            },
            passwordError = when {
                password.isEmpty() -> "יש להזין סיסמה"
                password.length < MIN_PASSWORD_LENGTH -> "הסיסמה חייבת להכיל לפחות 8 תווים"
                else -> null
            }
        )
        _errors.value = errors
        if (errors.nameError != null || errors.emailError != null || errors.passwordError != null) return

        _registerState.value = Resource.Loading
        viewModelScope.launch {
            _registerState.value = authRepository.registerWithEmail(trimmedName, trimmedEmail, password)
        }
    }

    fun registerWithGoogle(idToken: String) {
        if (isBusy) return
        _googleState.value = Resource.Loading
        viewModelScope.launch {
            _googleState.value = authRepository.registerWithGoogle(idToken)
        }
    }

    fun onGoogleFailed(message: String) {
        _googleState.value = Resource.Error(message)
    }

    fun completeGoogleProfile(name: String) {
        if (isBusy) return

        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _errors.value = RegisterErrors(nameError = "יש להזין שם")
            return
        }
        _errors.value = RegisterErrors()

        _completeState.value = Resource.Loading
        viewModelScope.launch {
            _completeState.value = authRepository.completeGoogleProfile(trimmed)
        }
    }

    /** Leaving the profile-completion screen without a profile: drop the half-created session. */
    fun cancelGoogleRegistration() {
        authRepository.logout()
    }

    fun clearRegisterState() { _registerState.value = null }
    fun clearGoogleState() { _googleState.value = null }
    fun clearCompleteState() { _completeState.value = null }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
