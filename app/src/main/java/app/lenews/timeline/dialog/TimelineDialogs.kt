package app.lenews.timeline.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lenews.R
import app.lenews.timeline.DialogState
import app.lenews.timeline.TimelineScreenModel
import app.lenews.timeline.TimelineState
import app.lenews.util.components.dialog.TwoChoicesDialog
import app.lenews.db.entities.OpenIn
import app.lenews.db.filters.OrderField
import app.lenews.db.filters.OrderType
import app.lenews.db.pojo.ItemWithFeed

@Composable
fun TimelineDialogs(
    state: TimelineState,
    screenModel: TimelineScreenModel,
    onOpenItem: (ItemWithFeed, OpenIn) -> Unit
) {
    when (val dialog = state.dialog) {
        is DialogState.ConfirmDialog -> {
            TwoChoicesDialog(
                title = stringResource(R.string.mark_all_articles_read),
                text = stringResource(R.string.mark_all_articles_read_question),
                icon = painterResource(id = R.drawable.ic_rss_feed_grey),
                confirmText = stringResource(id = R.string.validate),
                dismissText = stringResource(id = R.string.cancel),
                onDismiss = { screenModel.closeDialog() },
                onConfirm = {
                    screenModel.closeDialog()
                    screenModel.setAllItemsRead()
                }
            )
        }

        is DialogState.FilterSheet -> {
            FilterBottomSheet(
                filters = state.filters,
                onSetShowReadItems = {
                    screenModel.setShowReadItemsState(!state.filters.showReadItems)
                },
                onSetOrderField = {
                    screenModel.setOrderFieldState(
                        if (state.filters.orderField == OrderField.ID) {
                            OrderField.DATE
                        } else {
                            OrderField.ID
                        }
                    )
                },
                onSetOrderType = {
                    screenModel.setOrderTypeState(
                        if (state.filters.orderType == OrderType.DESC) {
                            OrderType.ASC
                        } else {
                            OrderType.DESC
                        }
                    )
                },
                onDismiss = { screenModel.closeDialog() }
            )
        }

        is DialogState.OpenIn -> {
            val itemWithFeed = dialog.itemWithFeed

            OpenInParameterDialog(
                openIn = itemWithFeed.openIn!!,
                onValidate = { openIn, openInAsk ->
                    screenModel.updateOpenInParameter(
                        feedId = itemWithFeed.feedId,
                        openIn = openIn,
                        openInAsk = openInAsk
                    )

                    screenModel.closeDialog(dialog)

                    onOpenItem(itemWithFeed, openIn)
                },
                onDismiss = { screenModel.closeDialog(dialog) }
            )
        }

        else -> {}
    }
}