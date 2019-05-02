package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.Config
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import rocks.xmpp.addr.Jid
import javax.inject.Inject

@ActivityScope
class PrivateMessageWith @Inject constructor(
    private val fragment: ConversationFragment
) : (Jid) -> Unit {
    override fun invoke(counterpart: Jid) = fragment.run {
        if (conversation!!.setOutgoingChatState(Config.DEFAULT_CHATSTATE)) {
            activity!!.xmppConnectionService.sendChatState(conversation)
        }
        this.binding!!.textinput.setText("")
        this.conversation!!.nextCounterpart = counterpart
        updateChatMsgHint()
        updateSendButton()
        updateEditablity()
    }
}