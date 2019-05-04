package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UpdateEditablity @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding
) : () -> Unit {
    override fun invoke() {
        val conversation = fragment.conversation!!
        val canWrite = conversation.run {
            mode == Conversation.MODE_SINGLE || mucOptions.participating() || nextCounterpart != null
        }
        binding.textinput.isFocusable = canWrite
        binding.textinput.isFocusableInTouchMode = canWrite
        binding.textSendButton.isEnabled = canWrite
        binding.textinput.isCursorVisible = canWrite
        binding.textinput.isEnabled = canWrite
    }
}