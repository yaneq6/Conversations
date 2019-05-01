package eu.siacs.conversations.feature.conversation.command

import android.view.View
import android.widget.AbsListView
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ToggleScrollDownButton @Inject constructor(
    val fragment: ConversationFragment
) : (AbsListView) -> Unit, () -> Unit {

    override fun invoke() = invoke(fragment.binding!!.messagesView)

    override fun invoke(listView: AbsListView): Unit = fragment.run {
        if (conversation == null) {
            return
        }
        if (scrolledToBottom()) {
            lastMessageUuid = null
            hideUnreadMessagesCount()
        } else {
            binding!!.scrollToBottomButton.isEnabled = true
            binding!!.scrollToBottomButton.show()
            if (lastMessageUuid == null) {
                lastMessageUuid = conversation!!.latestMessage.uuid
            }
            if (conversation!!.getReceivedMessagesCountSinceUuid(lastMessageUuid) > 0) {
                binding!!.unreadCountCustomView.visibility = View.VISIBLE
            }
        }
    }
}