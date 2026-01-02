package com.claudecode.native

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTestBase {
    protected val testDispatcher: TestDispatcher = StandardTestDispatcher()

    open fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    open fun tearDown() {
        Dispatchers.resetMain()
    }
}
