package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import io.aakit.scope.ActivityScope
import javax.inject.Inject


@ActivityScope
class HasMamSupport @Inject constructor() : (Conversation) -> Boolean {

    override fun invoke(conversation: Conversation): Boolean = conversation.run {
        if (mode != Conversation.MODE_SINGLE) mucOptions.mamSupport()
        else account.xmppConnection?.features?.mam() ?: false
    }
}