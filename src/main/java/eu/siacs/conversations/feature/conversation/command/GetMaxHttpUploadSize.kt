package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class GetMaxHttpUploadSize @Inject constructor(
    private val fragment: ConversationFragment
) : (Conversation) -> Long {
    override fun invoke(conversation: Conversation): Long = fragment.run {
        val connection = conversation.account.xmppConnection
        return connection?.features?.maxHttpUploadSize ?: -1
    }
}