package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import io.aakit.scope.ActivityScope
import javax.inject.Inject


@ActivityScope
class HasMamSupport @Inject constructor() : (Conversation) -> Boolean {
    override fun invoke(c: Conversation): Boolean =
        if (c.mode != Conversation.MODE_SINGLE) c.mucOptions.mamSupport()
        else c.account.xmppConnection?.features?.mam() ?: false

}