package eu.siacs.conversations.feature.xmpp.command

import eu.siacs.conversations.entities.Conversation
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HighlightInMuc @Inject constructor(
    private val switchToConversation: SwitchToConversation
) {
    operator fun invoke(conversation: Conversation, nick: String) {
        switchToConversation(conversation, null, false, nick, false, false)
    }
}