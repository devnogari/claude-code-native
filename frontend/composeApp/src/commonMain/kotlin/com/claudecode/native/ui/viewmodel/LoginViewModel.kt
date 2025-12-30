package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.AuthApi
import com.claudecode.native.data.model.TokenResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LoginViewModel(private val authApi: AuthApi) {
    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    suspend fun login(username: String, password: String) {
        _uiState.value = LoginUiState.Loading
        try {
            val response = authApi.login(username, password)
            _uiState.value = LoginUiState.Success(response)
        } catch (e: Exception) {
            _uiState.value = LoginUiState.Error(e.message ?: "Login failed")
        }
    }

    suspend fun register(username: String, password: String, confirmPassword: String) {
        if (password != confirmPassword) {
            _uiState.value = LoginUiState.Error("Passwords do not match")
            return
        }

        _uiState.value = LoginUiState.Loading
        try {
            val response = authApi.register(username, password, confirmPassword)
            _uiState.value = LoginUiState.Success(response)
        } catch (e: Exception) {
            _uiState.value = LoginUiState.Error(e.message ?: "Registration failed")
        }
    }

    fun clearError() {
        if (_uiState.value is LoginUiState.Error) {
            _uiState.value = LoginUiState.Idle
        }
    }
}

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data class Success(val response: TokenResponse) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}
