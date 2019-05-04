package eu.siacs.conversations.feature.conversation.callback

import android.content.IntentSender
import android.view.View
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.feature.conversation.REQUEST_DECRYPT_PGP
import eu.siacs.conversations.feature.conversation.command.UpdateSnackBar
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ClickToDecryptListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val updateSnackBar: UpdateSnackBar
) : View.OnClickListener {

    override fun onClick(view: View) {
        val conversation = fragment.conversation!!
        val pendingIntent = conversation.account.pgpDecryptionService.pendingIntent
        if (pendingIntent != null) {
            try {
                activity.startIntentSenderForResult(
                    pendingIntent.intentSender,
                    REQUEST_DECRYPT_PGP, null,
                    0,
                    0,
                    0
                )
            } catch (e: IntentSender.SendIntentException) {
                Toast.makeText(
                    activity,
                    R.string.unable_to_connect_to_keychain,
                    Toast.LENGTH_SHORT
                ).show()
                conversation.account.pgpDecryptionService.continueDecryption(true)
            }
        }
        updateSnackBar(conversation)
    }
}