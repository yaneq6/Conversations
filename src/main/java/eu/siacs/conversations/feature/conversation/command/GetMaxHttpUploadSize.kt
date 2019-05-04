package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class GetMaxHttpUploadSize @Inject constructor() : (Conversation) -> Long {
    override fun invoke(conversation: Conversation): Long = conversation
        .account.xmppConnection
        ?.features?.maxHttpUploadSize ?: -1
}