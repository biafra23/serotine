package eth.serotine.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction

/**
 * The keyboard's Done key on the last field of a form, doing what the form's button does: the
 * button may sit below the keyboard, and this spares the user scrolling down to it. The key
 * closes the keyboard and, when [canSubmit] (the button's own enabled state), runs [submit]; a
 * greyed-out button stays the user's to read once the keyboard is gone.
 */
internal fun submitOnDone(canSubmit: Boolean, submit: () -> Unit) = KeyboardActions(
    onDone = {
        defaultKeyboardAction(ImeAction.Done)
        if (canSubmit) submit()
    },
)

/**
 * The keyboard's Next key on a form's field: it moves to [field], the form's next field, by name
 * rather than by focus order, which would stop at a button or a chip drawn in between.
 */
internal fun nextTo(field: FocusRequester) = KeyboardActions(onNext = { field.requestFocus() })

/** [this] with the keyboard's action key showing Done, for a field that ends with [submitOnDone]. */
internal fun KeyboardOptions.done() = copy(imeAction = ImeAction.Done)

/** [this] with the keyboard's action key showing Next, for a field that goes on with [nextTo]. */
internal fun KeyboardOptions.next() = copy(imeAction = ImeAction.Next)
