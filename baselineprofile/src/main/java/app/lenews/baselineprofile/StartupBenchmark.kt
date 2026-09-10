/*
 * Copyright (C) 2026 Jean-Michel Nicolas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package app.lenews.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start, from launching the app to the frame the timeline is first drawn
 * in, ten times over.
 *
 * The two tests are the two ends of what a profile can buy, measured on one
 * APK. What they do **not** answer on their own is ticket 24's question, which
 * is whether the profile LeNews generates beats the one Compose and the other
 * AndroidX libraries embed in their own AARs: those are merged into a single
 * `assets/dexopt/baseline.prof` at build time, so telling them apart means
 * building the APK twice — once with the generated profile in
 * `app/src/main/generated/baselineProfiles/`, once without it — and running
 * [coldStartWithTheProfileTheApkCarries] against each.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * Nothing compiled ahead of time, so every class is loaded and every method
     * interpreted or JIT-ed on the way. The worst case, and the reference the
     * other number is read against.
     */
    @Test
    fun coldStartWithNothingCompiledAheadOfTime() = measure(CompilationMode.None())

    /**
     * Compiled from whatever baseline profile the APK carries, which is what a
     * reader who installed the APK gets. `Require` rather than `Enable`: if the
     * profile is not there or does not install, this is meant to fail rather
     * than quietly measure the case above a second time.
     */
    @Test
    fun coldStartWithTheProfileTheApkCarries() =
        measure(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun measure(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = {
            grantTheNotificationPermission()
            pressHome()
        }
    ) {
        startActivityAndWait()

        // Reaching the timeline is not part of the metric — that is read out of
        // the trace — but it is what makes the measurement mean anything: a
        // login screen also draws a first frame, and it draws it faster.
        waitForTheTimeline()
    }
}
