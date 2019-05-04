package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnTextChanged @Inject constructor(
    private val fragment: ConversationFragment,
    private val updateSendButton: UpdateSendButton
) {
    operator fun invoke() {
        val conversation = fragment.conversation!!
        if (conversation.correctingMessage != null) {
            updateSendButton()
        }
    }
}