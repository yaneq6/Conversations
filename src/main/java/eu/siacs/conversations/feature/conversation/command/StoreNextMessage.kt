package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class StoreNextMessage @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity,
    private val binding: FragmentConversationBinding
) {
    operator fun invoke(msg: String = binding.textinput.text?.toString() ?: ""): Boolean {
        val conversation = fragment.conversation!!

        val notArchived = conversation.status != Conversation.STATUS_ARCHIVED
        val participating = conversation.mode == Conversational.MODE_SINGLE
                || conversation.mucOptions.participating()
        val changed = conversation.setNextMessage(msg)

        return (notArchived && participating && changed).also {
            if (it) activity.xmppConnectionService.updateConversation(conversation)
        }
    }
}