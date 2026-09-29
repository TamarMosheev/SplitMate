package com.example.myapplication.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.User
import com.example.myapplication.repository.AuthRepository
import com.example.myapplication.utils.Resource
import com.example.myapplication.utils.ValidationUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginFormErrors(
    val emailError: String? = null,
    val passwordError: String? = null
)

class LoginViewModel(
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

    private val _loginState = MutableStateFlow<Resource<User>?>(null)
    val loginState: StateFlow<Resource<User>?> = _loginState.asStateFlow()

    private val _formErrors = MutableStateFlow(LoginFormErrors())
    val formErrors: StateFlow<LoginFormErrors> = _formErrors.asStateFlow()

    fun login(email: String, password: String) {
        val emailErr = ValidationUtils.validateEmail(email)
        val passwordErr = ValidationUtils.validatePassword(password)

        if (emailErr != null || passwordErr != null) {
            _formErrors.value = LoginFormErrors(emailError = emailErr, passwordError = passwordErr)
            return
        }

        _formErrors.value = LoginFormErrors()
        _loginState.value = Resource.Loading

        viewModelScope.launch {
            val result = authRepository.loginWithEmail(email, password)
            _loginState.value = result
        }
    }

    fun resetState() {
        _loginState.value = null
        _formErrors.value = LoginFormErrors()
    }
}