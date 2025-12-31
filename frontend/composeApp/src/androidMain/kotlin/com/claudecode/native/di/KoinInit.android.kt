package com.claudecode.native.di

import android.content.Context
import com.claudecode.native.data.storage.TokenStorage
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

/**
 * Initialize Koin for Android with application context.
 */
fun initKoin(context: Context) {
    // Only initialize if not already started
    if (GlobalContext.getOrNull() == null) {
        TokenStorage.init(context)
        startKoin {
            androidContext(context)
            modules(appModule)
        }
    }
}
