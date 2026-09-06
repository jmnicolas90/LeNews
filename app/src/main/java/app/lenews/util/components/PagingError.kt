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
package app.lenews.util.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lenews.R
import app.lenews.util.theme.ShortSpacer

/**
 * What a list shows when it could not load and has nothing to show instead: the
 * failure, and the way to ask again.
 *
 * The retry is the point. Without it the reader's only way out of a failed load
 * is to leave the screen and come back, and the screen that told them nothing
 * had failed — the empty placeholder this replaces — did not even give them a
 * reason to.
 */
@Composable
fun PagingErrorPlaceholder(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    CenteredColumn(modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_error),
            tint = MaterialTheme.colorScheme.primary,
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )

        ShortSpacer()

        Text(
            text = stringResource(R.string.error_occured),
            style = MaterialTheme.typography.titleLarge
        )

        ShortSpacer()

        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.retry))
        }
    }
}

/**
 * What a list shows next to the articles it already has when the page beyond
 * them failed to load: under them for the next page, above them for the page
 * before.
 *
 * The articles stay: a page that did not arrive is no reason to take away the
 * ones that did.
 */
@Composable
fun PagingErrorRow(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.error_occured),
            style = MaterialTheme.typography.bodyMedium
        )

        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.retry))
        }
    }
}
