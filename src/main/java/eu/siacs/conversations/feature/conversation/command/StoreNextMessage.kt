package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class StoreNextMessage @Inject constructor(
    private val fragment: ConversationFragment
) {
    operator fun invoke(msg: String = fragment.binding!!.textinput.text!!.toString()): Boolean = fragment.run {
        val participating =
            conversation!!.mode == Conversational.MODE_SINGLE || conversation!!.mucOptions.participating()
        if (this.conversation!!.status != Conversation.STATUS_ARCHIVED && participating && this.conversation!!.setNextMessage(
                msg
            )
        ) {
            this.activity!!.xmppConnectionService.updateConversation(this.conversation)
            return true
        }
        return false
    }
}