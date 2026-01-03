package com.claudecode.native.ui.viewmodel

import app.cash.turbine.test
import com.claudecode.native.ViewModelTestBase
import com.claudecode.native.data.api.AuthApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.api.SyncResult
import com.claudecode.native.data.model.TokenResponse
import com.claudecode.native.data.model.User
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest : ViewModelTestBase() {

    private lateinit var authApi: AuthApi
    private lateinit var projectApi: ProjectApi
    private lateinit var testScope: TestScope
    private lateinit var viewModel: LoginViewModel

    @BeforeTest
    fun setUp() {
        super.setup()
        authApi = mockk(relaxed = true)
        projectApi = mockk(relaxed = true)
        testScope = TestScope(testDispatcher)
        viewModel = LoginViewModel(authApi, projectApi, testScope)
    }

    @AfterTest
    fun cleanUp() {
        super.tearDown()
    }

    @Test
    fun `initial state should be Idle`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is LoginUiState.Idle)
        }
    }

    @Test
    fun `login with empty username should show error`() = runTest {
        viewModel.uiState.test {
            // Initial state
            assertTrue(awaitItem() is LoginUiState.Idle)

            // Attempt login with empty username
            viewModel.login("", "password123")

            // Should show error
            val errorState = awaitItem()
            assertTrue(errorState is LoginUiState.Error)
            assertEquals("Username and password are required", (errorState as LoginUiState.Error).message)
        }
    }

    @Test
    fun `login with empty password should show error`() = runTest {
        viewModel.uiState.test {
            // Initial state
            assertTrue(awaitItem() is LoginUiState.Idle)

            // Attempt login with empty password
            viewModel.login("testuser", "")

            // Should show error
            val errorState = awaitItem()
            assertTrue(errorState is LoginUiState.Error)
            assertEquals("Username and password are required", (errorState as LoginUiState.Error).message)
        }
    }

    @Test
    fun `successful login should transition through Loading to Success`() = runTest {
        val user = User(id = "user-id-123", username = "testuser")
        val tokenResponse = TokenResponse(
            token = "jwt-token-123",
            expiresAt = System.currentTimeMillis() + 3600000,
            user = user
        )
        coEvery { authApi.login(any(), any()) } returns tokenResponse
        coEvery { projectApi.sync() } returns SyncResult()

        viewModel.uiState.test {
            // Initial state
            assertTrue(awaitItem() is LoginUiState.Idle)

            // Trigger login
            viewModel.login("testuser", "password123")

            // Should transition to Loading
            assertTrue(awaitItem() is LoginUiState.Loading)

            // Advance dispatcher to process coroutine
            testDispatcher.scheduler.advanceUntilIdle()

            // Should transition to Success
            val successState = awaitItem()
            assertTrue(successState is LoginUiState.Success)
            assertEquals(tokenResponse, (successState as LoginUiState.Success).response)
        }
    }

    @Test
    fun `failed login should show error state`() = runTest {
        coEvery { authApi.login(any(), any()) } throws RuntimeException("Invalid credentials")

        viewModel.uiState.test {
            // Initial state
            assertTrue(awaitItem() is LoginUiState.Idle)

            // Trigger login
            viewModel.login("testuser", "wrongpassword")

            // Should transition to Loading
            assertTrue(awaitItem() is LoginUiState.Loading)

            // Advance dispatcher to process coroutine
            testDispatcher.scheduler.advanceUntilIdle()

            // Should transition to Error
            val errorState = awaitItem()
            assertTrue(errorState is LoginUiState.Error)
        }
    }

    @Test
    fun `clearError should reset to Idle when in Error state`() = runTest {
        viewModel.uiState.test {
            // Initial state
            assertTrue(awaitItem() is LoginUiState.Idle)

            // Trigger error
            viewModel.login("", "")
            assertTrue(awaitItem() is LoginUiState.Error)

            // Clear error
            viewModel.clearError()
            assertTrue(awaitItem() is LoginUiState.Idle)
        }
    }

    @Test
    fun `resetState should set state to Idle`() = runTest {
        val user = User(id = "user-id", username = "testuser")
        val tokenResponse = TokenResponse(
            token = "jwt-token",
            expiresAt = System.currentTimeMillis() + 3600000,
            user = user
        )
        coEvery { authApi.login(any(), any()) } returns tokenResponse
        coEvery { projectApi.sync() } returns SyncResult()

        viewModel.uiState.test {
            // Initial state
            assertTrue(awaitItem() is LoginUiState.Idle)

            // Trigger successful login
            viewModel.login("user", "pass")
            assertTrue(awaitItem() is LoginUiState.Loading)
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(awaitItem() is LoginUiState.Success)

            // Reset state
            viewModel.resetState()
            assertTrue(awaitItem() is LoginUiState.Idle)
        }
    }

    @Test
    fun `register with mismatched passwords should show error`() = runTest {
        viewModel.uiState.test {
            // Initial state
            assertTrue(awaitItem() is LoginUiState.Idle)

            // Attempt register with mismatched passwords
            viewModel.register("testuser", "password1", "password2")

            // Should show error
            val errorState = awaitItem()
            assertTrue(errorState is LoginUiState.Error)
            assertEquals("Passwords do not match", (errorState as LoginUiState.Error).message)
        }
    }

    @Test
    fun `register with empty fields should show error`() = runTest {
        viewModel.uiState.test {
            // Initial state
            assertTrue(awaitItem() is LoginUiState.Idle)

            // Attempt register with empty username
            viewModel.register("", "password", "password")

            // Should show error
            val errorState = awaitItem()
            assertTrue(errorState is LoginUiState.Error)
            assertEquals("Username and password are required", (errorState as LoginUiState.Error).message)
        }
    }
}
