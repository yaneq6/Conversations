package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class Refresh @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val updateSnackBar: UpdateSnackBar,
    private val updateStatusMessages: UpdateStatusMessages,
    private val binding: FragmentConversationBinding,
    private val fireReadEvent: FireReadEvent,
    private val updateChatMsgHint: UpdateChatMsgHint,
    private val updateSendButton: UpdateSendButton,
    private val updateEditablity: UpdateEditablity
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

    override fun invoke(notifyConversationRead: Boolean) {
        synchronized(fragment.messageList) {
            fragment.conversation?.let { conversation ->
                conversation.populateWithMessages(fragment.messageList)
                updateSnackBar(conversation)
                updateStatusMessages()
                if (conversation.getReceivedMessagesCountSinceUuid(fragment.lastMessageUuid) != 0) {
                    binding.unreadCountCustomView.visibility = View.VISIBLE
                    binding.unreadCountCustomView.setUnreadCount(
                        conversation.getReceivedMessagesCountSinceUuid(fragment.lastMessageUuid)
                    )
                }
                fragment.messageListAdapter.notifyDataSetChanged()
                updateChatMsgHint()
                if (notifyConversationRead) {
                    binding.messagesView.post { this.fireReadEvent() }
                }
                updateSendButton()
                updateEditablity()
                activity.invalidateOptionsMenu()
            }
        }
    }
}