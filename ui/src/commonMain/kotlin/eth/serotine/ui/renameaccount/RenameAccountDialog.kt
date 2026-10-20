package eth.serotine.ui.renameaccount

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.done
import eth.serotine.ui.components.submitOnDone

/** Test tag of the name field, for UI tests. */
const val RENAME_FIELD_TAG = "rename-field"

/** Binds [RenameAccountDialog] to its ViewModel; a saved name, or a vanished account, ends it through [onClose]. */
@Composable
fun RenameAccountRoute(viewModel: RenameAccountViewModel, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val finished = state is RenameAccountUiState.Done || state is RenameAccountUiState.Gone
    LaunchedEffect(finished) {
        if (finished) close()
    }
    RenameAccountDialog(state, viewModel::onNameChange, viewModel::save, onClose)
}

/** The rename dialog as a pure function of [state]. */
@Composable
fun RenameAccountDialog(
    state: RenameAccountUiState,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val editing = state as? RenameAccountUiState.Editing
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename account") },
        text = {
            when (state) {
                is RenameAccountUiState.Editing -> OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    modifier = Modifier.testTag(RENAME_FIELD_TAG),
                    label = { Text("Name") },
                    singleLine = true,
                    isError = state.error != null,
                    supportingText = state.error?.let { error -> { Text(error) } },
                    keyboardOptions = KeyboardOptions.Default.done(),
                    keyboardActions = submitOnDone(!state.saving, onSave),
                )
                is RenameAccountUiState.Unavailable -> Text(state.message)
                else -> Unit
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = editing != null && !editing.saving) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
