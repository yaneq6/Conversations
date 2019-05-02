package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.utils.UIHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UpdateChatMsgHint @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() = fragment.run {
        val multi = conversation!!.mode == Conversation.MODE_MULTI
        if (conversation!!.correctingMessage != null) {
            this.binding!!.textinput.setHint(R.string.send_corrected_message)
        } else if (multi && conversation!!.nextCounterpart != null) {
            this.binding!!.textinput.hint = getString(
                R.string.send_private_message_to,
                conversation!!.nextCounterpart.resource
            )
        } else if (multi && !conversation!!.mucOptions.participating()) {
            this.binding!!.textinput.setHint(R.string.you_are_not_participating)
        } else {
            this.binding!!.textinput.hint =
                UIHelper.getMessageHint(getActivity(), conversation!!)
            getActivity().invalidateOptionsMenu()
        }
    }
}