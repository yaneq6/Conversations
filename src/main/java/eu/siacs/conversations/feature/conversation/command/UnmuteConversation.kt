package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UnmuteConversation @Inject constructor(
    private val fragment: ConversationFragment
) : (Conversation) -> Unit {
    override fun invoke(conversation: Conversation) = fragment.run {
        conversation.setMutedTill(0)
        this.activity!!.xmppConnectionService.updateConversation(conversation)
        this.activity!!.onConversationsListItemUpdated()
        refresh()
        getActivity().invalidateOptionsMenu()
    }
}