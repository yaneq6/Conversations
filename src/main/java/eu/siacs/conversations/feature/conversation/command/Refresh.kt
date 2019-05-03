package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class Refresh @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) : (Boolean) -> Unit {

    operator fun invoke() = fragment.binding?.let {
        val conversation = fragment.conversation
        if (conversation != null && activity.xmppConnectionService != null) {
            if (!activity.xmppConnectionService.isConversationStillOpen(fragment.conversation)) {
                activity.onConversationArchived(fragment.conversation!!)
                return
            }
        }
        invoke(true)
    } ?: Timber.d("ConversationFragment.refresh() skipped updated because view binding was null")

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