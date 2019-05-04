package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.util.SoftKeyboardUtils
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnStop @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val storeNextMessage: StoreNextMessage,
    private val updateChatState: UpdateChatState
) {

    operator fun invoke() {
        val messageListAdapter = fragment.messageListAdapter
        messageListAdapter.unregisterListenerInAudioPlayer()
        if (!activity.isChangingConfigurations) {
            SoftKeyboardUtils.hideSoftKeyboard(activity)
            messageListAdapter.stopAudioPlayer()
        }
        val conversation = fragment.conversation
        if (conversation != null) {
            val msg = binding.textinput.text!!.toString()
            storeNextMessage(msg)
            updateChatState(conversation, msg)
            activity.xmppConnectionService!!.notificationService.setOpenConversation(null)
        }
        fragment.reInitRequiredOnStart = true
    }
}