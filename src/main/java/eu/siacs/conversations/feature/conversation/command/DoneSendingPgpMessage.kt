package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class DoneSendingPgpMessage @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() = fragment.mSendingPgpMessage.set(false)
}