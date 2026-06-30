package com.lin.hippyagent.di

import com.lin.hippyagent.core.privilege.ShizukuSystemApiBridge
import com.lin.hippyagent.core.privilege.SystemApiBridge
import org.koin.dsl.module

val privilegeModule = module {
    single<SystemApiBridge> { ShizukuSystemApiBridge(get()) }
}
