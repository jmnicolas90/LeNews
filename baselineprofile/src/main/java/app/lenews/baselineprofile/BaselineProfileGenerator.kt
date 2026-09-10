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

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes the baseline profile the app ships, by driving the app on a device and
 * recording every class and method ART loaded along the way.
 *
 * Not part of the gate and not part of any build: see the `baselineProfile`
 * block in app/build.gradle.kts for what keeps it out and how to run it. It
 * needs a device with a store on it — an empty store is a login screen, and a
 * profile recorded off a login screen describes the wrong app.
 *
 * **Two recordings, not one.** The first is the cold start and nothing else,
 * and it is the one marked `includeInStartupProfile`; the second is what the
 * reader does afterwards. The split is the whole point of the startup profile:
 * R8 uses it to put the classes read before the first frame next to each other
 * in the dex files, and a startup profile that names every class the app owns
 * says nothing about which of them come first. Recorded as one journey with the
 * flag on, that is exactly what came out — the startup profile and the full
 * profile were the same 29,035 lines.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Launching the app and getting as far as the first timeline. */
    @Test
    fun coldStart() = rule.collect(
        packageName = targetAppId,
        includeInStartupProfile = true
    ) {
        grantTheNotificationPermission()
        pressHome()
        startActivityAndWait()
        waitForTheTimeline()
    }

    /**
     * Reading: scrolling the timeline, which is where Paging loads and the item
     * rows are composed, and opening one article, which is where the WebView,
     * the HTML sanitiser and the reader's own pager are.
     */
    @Test
    fun scrollingAndReading() = rule.collect(
        packageName = targetAppId,
        includeInStartupProfile = false
    ) {
        grantTheNotificationPermission()
        pressHome()
        startActivityAndWait()
        scrollTheTimeline()
        openOneArticleAndComeBack()
    }
}
