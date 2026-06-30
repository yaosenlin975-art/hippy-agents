package com.lin.hippyagent.di

import com.lin.hippyagent.core.behavior.BehaviorRecordingController
import com.lin.hippyagent.core.deeplink.DeeplinkBookmarkSession
import com.lin.hippyagent.core.deeplink.DeeplinkIntentCapture
import com.lin.hippyagent.core.deeplink.DeeplinkLauncher
import com.lin.hippyagent.core.deeplink.DeeplinkSkillExporter
import com.lin.hippyagent.core.skill.SkillManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val deeplinkModule = module {
    single { DeeplinkIntentCapture(get()) }
    single { DeeplinkLauncher(androidContext(), get()) }
    single { DeeplinkSkillExporter(get<SkillManager>()) }
    single { DeeplinkBookmarkSession(get(), get(), get()) }
    single { BehaviorRecordingController(androidContext() as android.app.Application, get(), get()) }
}
