package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.xmpp.chatstate.ChatState
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnTypingStarted @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val updateSendButton: UpdateSendButton
) {
    operator fun invoke() {
        val conversation = fragment.conversation!!
        val service = (activity.xmppConnectionService) ?: return
        val status = conversation.account.status
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(
                ChatState.COMPOSING
            )) {
            service.sendChatState(conversation)
        }
        updateSendButton()
    }
}