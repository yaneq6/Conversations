package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.utils.UIHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UpdateChatMsgHint @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val activity: Activity
) : () -> Unit {
    override fun invoke() {
        val conversation = fragment.conversation!!
        val multi = conversation.mode == Conversation.MODE_MULTI
        if (conversation.correctingMessage != null) {
            binding.textinput.setHint(R.string.send_corrected_message)
        } else if (multi && conversation.nextCounterpart != null) {
            binding.textinput.hint = activity.getString(
                R.string.send_private_message_to,
                conversation.nextCounterpart.resource
            )
        } else if (multi && !conversation.mucOptions.participating()) {
            binding.textinput.setHint(R.string.you_are_not_participating)
        } else {
            binding.textinput.hint =
                UIHelper.getMessageHint(activity, conversation)
            activity.invalidateOptionsMenu()
        }
    }
}