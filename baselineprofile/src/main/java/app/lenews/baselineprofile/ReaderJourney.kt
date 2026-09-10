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

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

// The journey the profile is recorded from and the benchmarks replay, in one
// place so the two cannot drift apart. A baseline profile is only worth what the
// journey that produced it was worth: rules for code nobody runs at startup cost
// space and buy nothing, and a startup path the generator never walked is a path
// the profile does not cover.
//
// It is deliberately written against what the reader sees rather than against
// resource ids: Compose has none to find, and the two stores this runs on — a
// seeded emulator and a phone synced against the debug FreshRSS account — hold
// different articles under different titles. What both hold is one scrollable
// list of them.

/**
 * The application id of the build being driven. AGP knows it and the test does
 * not: the build types the baseline profile plugin adds are derived from
 * `release`, and what they end up called is the plugin's business, not this
 * file's. build.gradle.kts passes it in.
 */
val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error(
            "targetAppId was not passed to the instrumentation. It is set in" +
                    " baselineprofile/build.gradle.kts from the tested APK, so a run" +
                    " without it is a run that did not go through Gradle."
        )

/**
 * How many times each benchmark repeats its journey. Ten is enough for the
 * median to stop moving between runs on this hardware and short enough that the
 * whole matrix — two compilation modes, two benchmarks — fits in one sitting.
 */
const val BENCHMARK_ITERATIONS = 10

/** Long enough for a cold start on the slowest device this is run on. */
private const val TIMELINE_TIMEOUT_MILLIS = 20_000L

/** Long enough for an article's WebView to render on that same device. */
private const val ARTICLE_TIMEOUT_MILLIS = 15_000L

/**
 * Grants the notification permission, so that the timeline does not stop to ask
 * for it.
 *
 * `TimelineTab` requests POST_NOTIFICATIONS the first time it is composed, and
 * the system dialog it puts up sits over the list: the fling lands on the
 * dialog, the tap lands on "ALLOW", and the first frame that was meant to be
 * the timeline's is the permission controller's. Granting it up front is also
 * the truthful state to measure — a reader answers that dialog once, on the
 * first run, and never sees it again.
 *
 * Harmless when it has already been granted, which is why it is done on every
 * iteration rather than once and remembered.
 */
fun MacrobenchmarkScope.grantTheNotificationPermission() {
    device.executeShellCommand(
        "pm grant $targetPackage android.permission.POST_NOTIFICATIONS"
    )
}

/**
 * Waits for the timeline and answers with the list itself.
 *
 * The list is found by its scrollable semantics, which is the one thing the
 * timeline has and the login screen has not — so an empty store fails here,
 * loudly, saying what to do about it, rather than quietly measuring the cold
 * start of a login form and calling it a timeline.
 */
fun MacrobenchmarkScope.waitForTheTimeline(): UiObject2 =
    device.wait(Until.findObject(By.pkg(targetPackage).scrollable(true)), TIMELINE_TIMEOUT_MILLIS)
        ?: error(
            "no scrollable list appeared within $TIMELINE_TIMEOUT_MILLIS ms. The store is" +
                    " probably empty, which puts the login screen in front of the" +
                    " timeline: seed one with scripts/seed-store.sh, or sign the app in."
        )

/**
 * Flings the timeline down [flings] times, waiting for the frames of each fling
 * to be drawn before asking for the next. A fling rather than a slow drag,
 * because a fling is what makes Paging load the page below and what a reader
 * actually does to a list of a few hundred articles a day.
 */
fun MacrobenchmarkScope.scrollTheTimeline(flings: Int = 4) {
    val timeline = waitForTheTimeline()

    // Without a margin the gesture starts on the very edge of the display,
    // where the system's own back gesture takes it first.
    timeline.setGestureMargin(device.displayWidth / 5)

    repeat(flings) {
        timeline.fling(Direction.DOWN)
        device.waitForIdle()
    }
}

/**
 * Opens one article and comes back. Recorded so the profile covers the reader
 * as well as the list: the article screen is a WebView, an HTML sanitiser and a
 * pager, none of which the timeline touches.
 *
 * The tap lands in the middle of the display, which is inside the list and away
 * from both the swipe actions at its sides and the bars at its top and bottom.
 */
fun MacrobenchmarkScope.openOneArticleAndComeBack() {
    waitForTheTimeline()

    device.click(device.displayWidth / 2, device.displayHeight / 2)

    val opened = device.wait(
        Until.hasObject(By.clazz("android.webkit.WebView")),
        ARTICLE_TIMEOUT_MILLIS
    )
    check(opened) {
        "tapping the middle of the timeline did not open an article within" +
                " $ARTICLE_TIMEOUT_MILLIS ms"
    }

    device.pressBack()
    waitForTheTimeline()
}
