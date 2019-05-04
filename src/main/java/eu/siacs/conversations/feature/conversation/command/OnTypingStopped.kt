package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.xmpp.chatstate.ChatState
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnTypingStopped @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) {
    operator fun invoke() {
        val service = (activity.xmppConnectionService) ?: return
        val conversation = fragment.conversation!!
        val status = conversation.account.status
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(
                ChatState.PAUSED
            )) {
            service.sendChatState(conversation)
        }
    }
}