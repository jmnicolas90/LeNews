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
package app.lenews.db.queries

/**
 * The start of the "last 24 hours" window, in epoch milliseconds, as SQL.
 *
 * The filters below compare the stored milliseconds against this bound rather
 * than converting every row's date to text, which is what the inherited queries
 * did: a comparison can walk an index, a function call on every row cannot. The
 * bound is computed once per execution, so the window still moves as time
 * passes.
 */
const val LAST_24_HOURS_START = "(strftime('%s', 'now', '-1 day') * 1000)"

/** The "last 24 hours" filter, on an unqualified `pub_date`. */
const val WITHIN_LAST_24_HOURS = "pub_date >= $LAST_24_HOURS_START"
