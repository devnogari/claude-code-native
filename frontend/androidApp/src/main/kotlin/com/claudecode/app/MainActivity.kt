package com.claudecode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.claudecode.native.App
import com.claudecode.native.data.repository.PreferencesRepository
import com.claudecode.native.di.initKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PreferencesRepository.init(applicationContext)
        initKoin(applicationContext)
        setContent {
            App()
        }
    }
}
