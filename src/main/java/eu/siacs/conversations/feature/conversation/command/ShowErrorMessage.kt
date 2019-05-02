package eu.siacs.conversations.feature.conversation.command

import android.support.v7.app.AlertDialog
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowErrorMessage @Inject constructor(
    private val fragment: ConversationFragment
) : (Message) -> Unit {
    override fun invoke(message: Message) = fragment.run {
        val builder = AlertDialog.Builder(getActivity())
        builder.setTitle(R.string.error_message)
        val errorMessage = message.errorMessage
        val errorMessageParts =
            errorMessage?.split("\\u001f".toRegex())?.dropLastWhile { it.isEmpty() }?.toTypedArray()
                ?: arrayOfNulls<String>(0)
        val displayError: String?
        if (errorMessageParts.size == 2) {
            displayError = errorMessageParts[1]
        } else {
            displayError = errorMessage
        }
        builder.setMessage(displayError)
        builder.setNegativeButton(R.string.copy_to_clipboard) { dialog, which ->
            activity!!.copyTextToClipboard(displayError, R.string.error_message)
            Toast.makeText(
                activity,
                R.string.error_message_copied_to_clipboard,
                Toast.LENGTH_SHORT
            ).show()
        }
        builder.setPositiveButton(R.string.confirm, null)
        builder.create().show()
    }

}