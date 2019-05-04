package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.conversation.REQUEST_TRUST_KEYS_TEXT
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import java.util.*
import javax.inject.Inject


@ActivityScope
class SendMessage @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val commitAttachments: CommitAttachments,
    private val activity: XmppActivity,
    private val messageSent: MessageSent,
    private val trustKeysIfNeeded: TrustKeysIfNeeded,
    private val sendPgpMessage: SendPgpMessage
) :
        (Message) -> Unit,
        () -> Unit {

    override fun invoke(message: Message) {
        activity.xmppConnectionService.sendMessage(message)
        messageSent()
    }

    override fun invoke() {
        if (fragment.mediaPreviewAdapter!!.hasAttachments()) {
            commitAttachments()
            return
        }
        val conversation = fragment.conversation ?: return
        val body = binding.textinput.text?.toString()
        if (body.isNullOrEmpty()) return
        if (conversation.nextEncryption == Message.ENCRYPTION_AXOLOTL
            && trustKeysIfNeeded(REQUEST_TRUST_KEYS_TEXT)
        ) return
        val message: Message
        if (conversation.correctingMessage == null) {
            message = Message(conversation, body, conversation.nextEncryption)
            if (conversation.mode == Conversation.MODE_MULTI) {
                val nextCounterpart = conversation.nextCounterpart
                if (nextCounterpart != null) {
                    message.counterpart = nextCounterpart
                    message.trueCounterpart =
                        conversation.mucOptions.getTrueCounterpart(nextCounterpart)
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
            else -> invoke(message)
        }
    }
}