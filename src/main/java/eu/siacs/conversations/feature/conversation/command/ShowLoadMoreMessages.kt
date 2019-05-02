package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowLoadMoreMessages @Inject constructor(
    private val fragment: ConversationFragment
) : (Conversation?) -> Boolean {
    override fun invoke(c: Conversation?): Boolean = fragment.run {
        if (activity == null || activity!!.xmppConnectionService == null) {
            return false
        }
        val mam = hasMamSupport(c!!) && !c.contact.isBlocked
        val service = activity!!.xmppConnectionService.messageArchiveService
        return mam && (c.lastClearHistory.timestamp != 0L || c.countMessages() == 0 && c.messagesLoaded.get() && c.hasMessagesLeftOnServer() && !service.queryInProgress(
            c
        ))
    }
}