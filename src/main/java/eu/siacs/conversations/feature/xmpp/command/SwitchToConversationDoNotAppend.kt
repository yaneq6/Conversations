package eu.siacs.conversations.feature.xmpp.command

import eu.siacs.conversations.entities.Conversation
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SwitchToConversationDoNotAppend @Inject constructor(
    private val switchToConversation: SwitchToConversation
) {
    operator fun invoke(conversation: Conversation, text: String) {
        switchToConversation(conversation, text, false, null, false, true)
    }
}