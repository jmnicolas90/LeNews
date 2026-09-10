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
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first scroll of the timeline after a cold start, ten times over, measured
 * as the time each frame took to produce.
 *
 * A fresh process every time, on purpose. A baseline profile is about the work
 * a process does once — loading classes, compiling methods the first time they
 * run — so a scroll measured on a process that has already scrolled measures
 * nothing this ticket is asking about.
 *
 * The process is killed in the setup block rather than by passing
 * `startupMode = StartupMode.COLD`, and the difference is not cosmetic: that
 * argument kills the process **after** the setup block runs, which is right for
 * a benchmark that launches the app inside the measured block and wrong for
 * this one. Passed here it left every iteration measuring the launcher, with
 * the app never started at all.
 *
 * Read the same way as [StartupBenchmark]: the comparison ticket 24 needs is
 * [scrollWithTheProfileTheApkCarries] against two APKs, one built with the
 * generated profile and one without it.
 */
@RunWith(AndroidJUnit4::class)
class TimelineScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollWithNothingCompiledAheadOfTime() = measure(CompilationMode.None())

    @Test
    fun scrollWithTheProfileTheApkCarries() =
        measure(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun measure(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilationMode,
        iterations = BENCHMARK_ITERATIONS,
        setupBlock = {
            grantTheNotificationPermission()
            pressHome()
            killProcess()
            startActivityAndWait()

            // Waiting for the list here and not in the measured block, so that
            // what is measured is the scroll and not the store's first read.
            waitForTheTimeline()
        }
    ) {
        scrollTheTimeline()
    }
}
