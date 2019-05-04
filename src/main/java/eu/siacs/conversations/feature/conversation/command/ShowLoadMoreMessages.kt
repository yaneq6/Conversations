package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowLoadMoreMessages @Inject constructor(
    private val activity: XmppActivity,
    private val hasMamSupport: HasMamSupport
) : (Conversation) -> Boolean {
    override fun invoke(conversation: Conversation) = activity.xmppConnectionService?.run {
        conversation.run {
            lastClearHistory.timestamp != 0L
                    || countMessages() == 0
                    && messagesLoaded.get()
                    && hasMessagesLeftOnServer()
                    && !messageArchiveService.queryInProgress(conversation)
                    && !contact.isBlocked
                    && hasMamSupport(conversation)
        }
    } ?: false
}