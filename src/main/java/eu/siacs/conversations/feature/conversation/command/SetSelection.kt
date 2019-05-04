package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.util.ListViewUtils
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SetSelection @Inject constructor(
    private val binding: FragmentConversationBinding,
    private val fireReadEvent: FireReadEvent
) : (Int, Boolean) -> Unit {
    override fun invoke(pos: Int, jumpToBottom: Boolean) {
        ListViewUtils.setSelection(binding.messagesView, pos, jumpToBottom)
        binding.messagesView.post {
            ListViewUtils.setSelection(
                binding.messagesView,
                pos,
                jumpToBottom
            )
        }
        binding.messagesView.post { fireReadEvent() }
    }
}