package com.example.myapplication.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.AuthRepository
import com.example.myapplication.utils.Resource
import com.example.myapplication.utils.ValidationUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NewPasswordErrors(
    val passwordError: String? = null,
    val confirmError: String? = null
)

class NewPasswordViewModel(
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

    private val _errors = MutableStateFlow(NewPasswordErrors())
    val errors: StateFlow<NewPasswordErrors> = _errors.asStateFlow()

    private val _updateState = MutableStateFlow<Resource<Unit>?>(null)
    val updateState: StateFlow<Resource<Unit>?> = _updateState.asStateFlow()

    fun updatePassword(oobCode: String?, password: String, confirm: String) {
        if (_updateState.value is Resource.Loading) return

        val passwordError = ValidationUtils.validatePassword(password)
        val confirmError = when {
            confirm.isEmpty() -> "נא להזין אימות סיסמה"
            password != confirm -> "הסיסמאות אינן תואמות"
            else -> null
        }
        _errors.value = NewPasswordErrors(passwordError, confirmError)
        if (passwordError != null || confirmError != null) return

        if (oobCode.isNullOrBlank()) {
            _updateState.value = Resource.Error("קישור האיפוס אינו תקף. בקשו קישור חדש")
            return
        }

        _updateState.value = Resource.Loading
        viewModelScope.launch {
            _updateState.value = authRepository.confirmPasswordReset(oobCode, password)
        }
    }

    fun clearUpdateState() {
        _updateState.value = null
    }
}
