package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class RetryDecryption @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val refresh: Refresh
) : (Message) -> Unit {
    override fun invoke(message: Message) {
        message.encryption = Message.ENCRYPTION_PGP
        activity.onConversationsListItemUpdated()
        refresh()
        fragment.conversation!!.account.pgpDecryptionService.decrypt(message, false)
    }
}