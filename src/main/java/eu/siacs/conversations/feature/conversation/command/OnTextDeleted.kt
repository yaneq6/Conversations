package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.Config
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnTextDeleted @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val storeNextMessage: StoreNextMessage,
    private val updateSendButton: UpdateSendButton
) {
    operator fun invoke() {
        val service = (activity.xmppConnectionService) ?: return
        val conversation = fragment.conversation!!
        val status = conversation.account.status
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(
                Config.DEFAULT_CHATSTATE
            )) {
            service.sendChatState(conversation)
        }
        if (storeNextMessage()) {
            activity.onConversationsListItemUpdated()
        }
        updateSendButton()
    }
}