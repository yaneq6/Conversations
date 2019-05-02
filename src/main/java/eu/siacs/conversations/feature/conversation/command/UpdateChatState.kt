package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.Config
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.xmpp.chatstate.ChatState
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UpdateChatState @Inject constructor(
    private val fragment: ConversationFragment
) : (Conversation, String) -> Unit {
    override fun invoke(conversation: Conversation, msg: String) = fragment.run {
        val state = if (msg.length == 0) Config.DEFAULT_CHATSTATE else ChatState.PAUSED
        val status = conversation.account.status
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(state)) {
            activity!!.xmppConnectionService.sendChatState(conversation)
        }
    }
}