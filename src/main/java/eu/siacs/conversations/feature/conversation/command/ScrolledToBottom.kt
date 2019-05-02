package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ScrolledToBottom @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Boolean {
    override fun invoke(): Boolean = fragment.run {
        return this.binding != null && ConversationFragment.scrolledToBottom(this.binding!!.messagesView)
    }
}