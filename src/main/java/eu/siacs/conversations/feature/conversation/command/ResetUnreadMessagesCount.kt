package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ResetUnreadMessagesCount @Inject constructor(
    private val fragment: ConversationFragment,
    private val hideUnreadMessagesCount: HideUnreadMessagesCount
) : () -> Unit {
    override fun invoke() {
        fragment.lastMessageUuid = null
        hideUnreadMessagesCount()
    }
}