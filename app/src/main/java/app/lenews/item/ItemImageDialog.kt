package app.lenews.item

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lenews.R
import app.lenews.util.DefaultPreview
import app.lenews.util.components.SelectableIconText
import app.lenews.util.components.dialog.BaseDialog
import app.lenews.util.theme.spacing

enum class ItemImageChoice {
    SHARE,
    DOWNLOAD
}

@Composable
fun ItemImageDialog(
    onChoice: (ItemImageChoice) -> Unit,
    onDismiss: () -> Unit
) {
    BaseDialog(
        title = stringResource(id = R.string.image_options),
        icon = painterResource(id = R.drawable.ic_image),
        onDismiss = onDismiss
    ) {
        Column {
            SelectableIconText(
                icon = rememberVectorPainter(image = Icons.Default.Share),
                text = stringResource(id = R.string.share_image),
                style = MaterialTheme.typography.titleMedium,
                spacing = MaterialTheme.spacing.mediumSpacing,
                padding = MaterialTheme.spacing.shortSpacing,
                onClick = { onChoice(ItemImageChoice.SHARE) }
            )

            SelectableIconText(
                icon = painterResource(id = R.drawable.ic_download),
                text = stringResource(id = R.string.download_image),
                style = MaterialTheme.typography.titleMedium,
                spacing = MaterialTheme.spacing.mediumSpacing,
                padding = MaterialTheme.spacing.shortSpacing,
                onClick = { onChoice(ItemImageChoice.DOWNLOAD) }
            )
        }
    }
}

@DefaultPreview
@Composable
private fun ItemImageDialogPreview() {
    ItemImageDialog(
        onChoice = {},
        onDismiss = {}
    )
}