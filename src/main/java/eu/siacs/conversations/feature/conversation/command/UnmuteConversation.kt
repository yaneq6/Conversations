package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UnmuteConversation @Inject constructor(
    private val activity: ConversationsActivity,
    private val refresh: Refresh
) : (Conversation) -> Unit {
    override fun invoke(conversation: Conversation) {
        conversation.setMutedTill(0)
        activity.xmppConnectionService.updateConversation(conversation)
        activity.onConversationsListItemUpdated()
        refresh()
        activity.invalidateOptionsMenu()
    }
}