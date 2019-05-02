package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class CancelTransmission @Inject constructor(
    private val activity: XmppActivity
) : (Message) -> Unit {
    override fun invoke(message: Message) {
        val transferable = message.transferable
        if (transferable != null) {
            transferable.cancel()
        } else if (message.status != Message.STATUS_RECEIVED) {
            activity.xmppConnectionService.markMessage(
                message,
                Message.STATUS_SEND_FAILED,
                Message.ERROR_MESSAGE_CANCELLED
            )
        }
    }
}