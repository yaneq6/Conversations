package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.DateSeparator
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UpdateDateSeparators @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() = fragment.run {
        synchronized(messageList) {
            DateSeparator.addAll(messageList)
        }
    }
}