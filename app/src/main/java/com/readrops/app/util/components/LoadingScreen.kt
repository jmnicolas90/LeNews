package com.readrops.app.util.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.readrops.app.util.theme.MediumSpacer

@Composable
fun LoadingScreen(
    isRefreshing: Boolean
) {
    CenteredColumn {

        if (isRefreshing) {
            Text(
                text = "Refreshing...",
                style = MaterialTheme.typography.labelLarge
            )

            MediumSpacer()
        }
        CircularProgressIndicator()
    }
}