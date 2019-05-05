package eu.siacs.conversations.feature.conversation.command

import android.support.v7.app.AlertDialog
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowErrorMessage @Inject constructor(
    private val activity: XmppActivity
) : (Message) -> Unit {
    override fun invoke(message: Message) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(R.string.error_message)
        val errorMessage = message.errorMessage

        val errorMessageParts = errorMessage
            ?.split("\\u001f".toRegex())
            ?.dropLastWhile { it.isEmpty() }
            ?.toTypedArray()
            ?: arrayOfNulls<String>(0)

        val displayError: String = errorMessageParts.getOrNull(1) ?: errorMessage

        builder.setMessage(displayError)
        builder.setNegativeButton(R.string.copy_to_clipboard) { _, _ ->
            activity.copyTextToClipboard(displayError, R.string.error_message)
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