package eu.siacs.conversations.feature.conversation.command

import android.view.View
import android.widget.AbsListView
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ToggleScrollDownButton @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val scrolledToBottom: ScrolledToBottom,
    private val hideUnreadMessagesCount: HideUnreadMessagesCount
) : (AbsListView) -> Unit, () -> Unit {

    override fun invoke() = invoke(fragment.binding!!.messagesView)

    override fun invoke(listView: AbsListView) {
        val conversation = fragment.conversation ?: return
        if (scrolledToBottom()) {
            fragment.lastMessageUuid = null
            hideUnreadMessagesCount()
        } else {
            binding.scrollToBottomButton.isEnabled = true
            binding.scrollToBottomButton.show()
            if (fragment.lastMessageUuid == null) {
                fragment.lastMessageUuid = conversation.latestMessage.uuid
            }
            if (conversation.getReceivedMessagesCountSinceUuid(fragment.lastMessageUuid) > 0) {
                binding.unreadCountCustomView.visibility = View.VISIBLE
            }
        }
    }
}