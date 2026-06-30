package com.lin.hippyagent.di

import com.lin.hippyagent.core.deeplink.DeeplinkIntentCapture
import com.lin.hippyagent.core.deeplink.DeeplinkLauncher
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val deeplinkModule = module {
    single { DeeplinkIntentCapture(get()) }
    single { DeeplinkLauncher(androidContext(), get()) }
}
