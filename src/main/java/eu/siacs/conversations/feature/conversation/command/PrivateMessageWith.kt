package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.Config
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import rocks.xmpp.addr.Jid
import javax.inject.Inject

@ActivityScope
class PrivateMessageWith @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity,
    private val binding: FragmentConversationBinding,
    private val updateChatMsgHint: UpdateChatMsgHint,
    private val updateSendButton: UpdateSendButton,
    private val updateEditablity: UpdateEditablity
) : (Jid) -> Unit {
    override fun invoke(counterpart: Jid) {
        val conversation = fragment.conversation!!
        if (conversation.setOutgoingChatState(Config.DEFAULT_CHATSTATE)) {
            activity.xmppConnectionService.sendChatState(conversation)
        }
        binding.textinput.setText("")
        conversation.nextCounterpart = counterpart
        updateChatMsgHint()
        updateSendButton()
        updateEditablity()
    }
}