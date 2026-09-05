package app.lenews.util.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.lenews.util.theme.MediumSpacer

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