package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.AuthApi
import com.claudecode.native.data.model.TokenResponse
import com.claudecode.native.util.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authApi: AuthApi,
    private val scope: CoroutineScope
) {
    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = LoginUiState.Error("Username and password are required")
            return
        }

        scope.launch {
            _uiState.value = LoginUiState.Loading
            try {
                val response = authApi.login(username, password)
                _uiState.value = LoginUiState.Success(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = LoginUiState.Error(e.toUserMessage())
            }
        }
    }

    fun register(username: String, password: String, confirmPassword: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = LoginUiState.Error("Username and password are required")
            return
        }

        if (password != confirmPassword) {
            _uiState.value = LoginUiState.Error("Passwords do not match")
            return
        }

        scope.launch {
            _uiState.value = LoginUiState.Loading
            try {
                val response = authApi.register(username, password, confirmPassword)
                _uiState.value = LoginUiState.Success(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = LoginUiState.Error(e.toUserMessage())
            }
        }
    }

    fun clearError() {
        if (_uiState.value is LoginUiState.Error) {
            _uiState.value = LoginUiState.Idle
        }
    }

    fun resetState() {
        _uiState.value = LoginUiState.Idle
    }
}

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data class Success(val response: TokenResponse) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}
