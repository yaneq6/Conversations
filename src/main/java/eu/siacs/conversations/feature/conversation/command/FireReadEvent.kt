package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class FireReadEvent @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : () -> Unit {
    override fun invoke() {
        fragment.conversation?.let { conversation ->
            fragment.lastVisibleMessageUuid?.let { uuid ->
                activity.onConversationRead(conversation, uuid)
            }
        }
    }
}