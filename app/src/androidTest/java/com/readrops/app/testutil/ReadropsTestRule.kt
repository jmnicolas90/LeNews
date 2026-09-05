package com.readrops.app.testutil

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.readrops.api.apiModule
import com.readrops.app.appModule
import com.readrops.db.Database
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.Koin
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/**
 * What every app instrumented test needs before it runs: a Koin graph with an
 * in-memory database, and the notification permission.
 *
 * The permission is here rather than in the CI workflow — where upstream kept it,
 * as two `adb shell pm grant` lines — because the tests that need it need it
 * wherever they run. Left ungranted, `SyncWorkerTest` finds no notification to
 * inspect, since the system drops a notification the app has no permission to
 * post. A permission granted by the test rules travels with the tests; one
 * granted by a CI step is a trap for the next person who runs them by hand.
 *
 * POST_NOTIFICATIONS only exists from API 33, and asking for a permission the
 * platform does not know makes GrantPermissionRule fail during setup. The app's
 * floor is API 31, so the grant is asked for only where there is something to
 * grant; below 33 notifications need no runtime permission and the tests that
 * depend on them work without one.
 */
class ReadropsTestRule : TestRule {

    private val koinRule = KoinRule()

    private val chain: RuleChain =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuleChain
                .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
                .around(koinRule)
        } else {
            RuleChain.outerRule(koinRule)
        }

    val koin: Koin
        get() = koinRule.koin

    override fun apply(base: Statement, description: Description): Statement =
        chain.apply(base, description)

    @OptIn(KoinInternalApi::class)
    private class KoinRule : TestWatcher() {

        private var _koin: Koin? = null
        val koin: Koin
            get() = _koin ?: error("No Koin application found")

        override fun starting(description: Description?) {
            closeExistingInstance()
            _koin = startKoin {
                androidLogger(Level.INFO)
                androidContext(ApplicationProvider.getApplicationContext<Context>())

                modules(
                    module {
                        single {
                            Room.inMemoryDatabaseBuilder(get(), Database::class.java)
                                .build()
                        }
                    },
                    apiModule, appModule
                )
            }.koin

            koin.logger.info("Koin Rule - starting")
        }

        private fun closeExistingInstance() {
            KoinPlatformTools.defaultContext().getOrNull()?.let { koin ->
                koin.logger.info("Koin Rule - closing existing instance")
                koin.close()
            }
        }

        override fun finished(description: Description?) {
            koin.logger.info("Koin Rule - finished")
            stopKoin()
            _koin = null
        }
    }
}
