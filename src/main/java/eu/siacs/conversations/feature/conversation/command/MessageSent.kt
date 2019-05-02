package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import android.os.Handler
import android.preference.PreferenceManager
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class MessageSent @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: Activity,
    private val binding: FragmentConversationBinding,
    private val storeNextMessage: StoreNextMessage,
    private val updateChatMsgHint: UpdateChatMsgHint,
    private val scrolledToBottom: ScrolledToBottom
) : () -> Unit {
    override fun invoke() {
        val conversation = fragment.conversation!!
        fragment.mSendingPgpMessage.set(false)
        binding.textinput.setText("")
        if (conversation.setCorrectingMessage(null)) {
            binding.textinput.append(conversation.draftMessage)
            conversation.draftMessage = null
        }
        storeNextMessage()
        updateChatMsgHint()
        val p = PreferenceManager.getDefaultSharedPreferences(activity)
        val prefScrollToBottom =
            p.getBoolean("scroll_to_bottom", activity!!.resources.getBoolean(R.bool.scroll_to_bottom))
        if (prefScrollToBottom || scrolledToBottom()) {
            Handler().post {
                val size = fragment.messageList.size
                binding.messagesView.setSelection(size - 1)
            }
        }
    }
}