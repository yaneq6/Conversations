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
    private val activity: XmppActivity,
    private val fragment: ConversationFragment,
    private val commitAttachments: CommitAttachments
) : () -> Unit {
    override fun invoke(): Unit = activity.run {
        if (fragment.mediaPreviewAdapter!!.hasAttachments()) {
            commitAttachments()
            return
        }
        val text = fragment.binding!!.textinput.text
        val body = text?.toString() ?: ""
        val conversation = fragment.conversation
        if (body.length == 0 || conversation == null) {
            return
        }
        if (conversation.nextEncryption == Message.ENCRYPTION_AXOLOTL && fragment.trustKeysIfNeeded(
                ConversationFragment.REQUEST_TRUST_KEYS_TEXT
            )) {
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
            Message.ENCRYPTION_PGP -> fragment.sendPgpMessage(message)
            else -> fragment.sendMessage(message)
        }
    }
}