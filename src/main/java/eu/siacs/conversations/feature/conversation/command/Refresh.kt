package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class Refresh @Inject constructor(
    private val fragment: ConversationFragment
) : (Boolean) -> Unit {
    override fun invoke(notifyConversationRead: Boolean) = fragment.run {
        synchronized(this.messageList) {
            if (this.conversation != null) {
                conversation!!.populateWithMessages(this.messageList)
                updateSnackBar(conversation!!)
                updateStatusMessages()
                if (conversation!!.getReceivedMessagesCountSinceUuid(lastMessageUuid) != 0) {
                    binding!!.unreadCountCustomView.visibility = View.VISIBLE
                    binding!!.unreadCountCustomView.setUnreadCount(
                        conversation!!.getReceivedMessagesCountSinceUuid(
                            lastMessageUuid
                        )
                    )
                }
                this.messageListAdapter.notifyDataSetChanged()
                updateChatMsgHint()
                if (notifyConversationRead && activity != null) {
                    binding!!.messagesView.post { this.fireReadEvent() }
                }
                updateSendButton()
                updateEditablity()
                activity!!.invalidateOptionsMenu()
            }
        }
    }
}