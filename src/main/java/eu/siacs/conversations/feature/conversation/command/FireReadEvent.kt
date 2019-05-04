package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.feature.conversation.query.GetLastVisibleMessageUuid
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class FireReadEvent @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val getLastVisibleMessageUuid: GetLastVisibleMessageUuid
) : () -> Unit {
    override fun invoke() {
        fragment.conversation?.let { conversation ->
            getLastVisibleMessageUuid()?.let { uuid ->
                activity.onConversationRead(conversation, uuid)
            }
        }
    }
}