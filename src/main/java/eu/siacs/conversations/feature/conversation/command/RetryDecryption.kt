package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class RetryDecryption @Inject constructor(
    private val fragment: ConversationFragment
) : (Message) -> Unit {
    override fun invoke(message: Message) = fragment.run {
        message.encryption = Message.ENCRYPTION_PGP
        activity!!.onConversationsListItemUpdated()
        refresh()
        conversation!!.account.pgpDecryptionService.decrypt(message, false)
        Unit
    }
}