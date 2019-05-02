package eu.siacs.conversations.feature.conversation.command

import android.app.PendingIntent
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class EncryptTextMessage @Inject constructor(
    private val activity: XmppActivity,
    private val startPendingIntent: StartPendingIntent,
    private val messageSent: MessageSent,
    private val doneSendingPgpMessage: DoneSendingPgpMessage
) : (Message) -> Unit {
    override fun invoke(message: Message) {
        activity.xmppConnectionService.pgpEngine!!.encrypt(
            message,
            object : UiCallback<Message> {

                override fun userInputRequried(pi: PendingIntent, message: Message) {
                    startPendingIntent(
                        pi,
                        ConversationFragment.REQUEST_SEND_MESSAGE
                    )
                }

                override fun success(message: Message) {
                    //TODO the following two call can be made before the callback
                    activity.runOnUiThread { messageSent() }
                }

                override fun error(error: Int, message: Message) {
                    activity.runOnUiThread {
                        doneSendingPgpMessage()
                        Toast.makeText(
                            activity,
                            if (error == 0) R.string.unable_to_connect_to_keychain else error,
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                }
            }
        )
    }
}