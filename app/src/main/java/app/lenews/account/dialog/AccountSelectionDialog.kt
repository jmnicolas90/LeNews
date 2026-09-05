package app.lenews.account.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lenews.R
import app.lenews.account.selection.adaptiveIconPainterResource
import app.lenews.util.components.SelectableImageText
import app.lenews.util.components.dialog.BaseDialog
import app.lenews.util.theme.spacing
import app.lenews.db.entities.account.AccountType

@Composable
fun AccountSelectionDialog(
    onDismiss: () -> Unit,
    onValidate: (AccountType) -> Unit,
) {
    BaseDialog(
        title = stringResource(R.string.new_account),
        icon = painterResource(id = R.drawable.ic_add_account),
        onDismiss = onDismiss
    ) {
        AccountType.entries
            .forEach { type ->
                SelectableImageText(
                    image = adaptiveIconPainterResource(id = type.iconRes),
                    text = stringResource(id = type.nameRes),
                    style = MaterialTheme.typography.titleMedium,
                    spacing = MaterialTheme.spacing.mediumSpacing,
                    padding = MaterialTheme.spacing.shortSpacing,
                    imageSize = 36.dp,
                    onClick = { onValidate(type) }
                )
            }
    }
}