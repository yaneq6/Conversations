package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import java.util.*
import javax.inject.Inject


@ActivityScope
class SendMessage @Inject constructor(
    private val fragment: ConversationFragment,
    private val commitAttachments: CommitAttachments
) :
        (Message) -> Unit,
        () -> Unit {

    override fun invoke(message: Message) = fragment.run {
        activity!!.xmppConnectionService.sendMessage(message)
        messageSent()
    }

    override fun invoke(): Unit = fragment.run {
        if (mediaPreviewAdapter!!.hasAttachments()) {
            commitAttachments()
            return
        }
        val text = binding!!.textinput.text
        val body = text?.toString() ?: ""
        val conversation = conversation
        if (body.isEmpty() || conversation == null) {
            return
        }
        if (conversation.nextEncryption == Message.ENCRYPTION_AXOLOTL && trustKeysIfNeeded(
                ConversationFragment.REQUEST_TRUST_KEYS_TEXT
            )
        ) {
            return
        }
        val message: Message
        if (conversation.correctingMessage == null) {
            message = Message(conversation, body, conversation.nextEncryption)
            if (conversation.mode == Conversation.MODE_MULTI) {
                val nextCounterpart = conversation.nextCounterpart
                if (nextCounterpart != null) {
                    message.counterpart = nextCounterpart
                    message.trueCounterpart = conversation.mucOptions.getTrueCounterpart(nextCounterpart)
                    message.type = Message.TYPE_PRIVATE
                }
            }
        } else {
            message = conversation.correctingMessage
            message.body = body
            message.putEdited(message.uuid, message.serverMsgId)
            message.serverMsgId = null
            message.uuid = UUID.randomUUID().toString()
        }
        when (conversation.nextEncryption) {
            Message.ENCRYPTION_PGP -> sendPgpMessage(message)
            else -> sendMessage(message)
        }
    }
}