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
package app.lenews.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A coroutine scope owned by the application instead of by a screen. There is
 * one of it, held by Koin for as long as the process lives.
 *
 * What it is for: a decision the reader makes — an article read, an article
 * marked unread, an article starred — has to reach the store even if the screen
 * it was made on is gone the moment after. A screen's own scope is cancelled
 * when the screen is disposed, so a write that is waiting for Room's
 * transaction executor, behind a sync that is holding it, is cancelled before
 * it commits and nothing writes it later. Work that must finish is launched
 * here instead.
 *
 * It is not a general dumping ground: anything the screen alone cares about —
 * reading a preference, loading a page, downloading an image — belongs to the
 * screen's scope and should stop when the screen does.
 *
 * The job is a [SupervisorJob] so that one failed write does not take the scope
 * down with it, and the handler logs what failed, because there is no caller
 * left to tell.
 */
class ApplicationScope : CoroutineScope by CoroutineScope(
    SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "a write that has outlived its screen failed", error)
    }
) {

    private companion object {
        const val TAG = "ApplicationScope"
    }
}
