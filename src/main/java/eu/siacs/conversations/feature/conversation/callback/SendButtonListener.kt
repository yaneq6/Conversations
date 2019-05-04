package eu.siacs.conversations.feature.conversation.callback

import android.view.View
import android.view.View.OnClickListener
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.feature.conversation.command.*
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.SendButtonAction
import eu.siacs.conversations.ui.util.SendButtonAction.*
import io.aakit.scope.ActivityScope
import javax.inject.Inject


@ActivityScope
class SendButtonListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val updateChatMsgHint: UpdateChatMsgHint,
    private val updateSendButton: UpdateSendButton,
    private val updateEditablity: UpdateEditablity,
    private val sendMessage: SendMessage,
    private val attachFile: AttachFile
) : OnClickListener {

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is SendButtonAction) when (tag) {

            TAKE_PHOTO,
            RECORD_VIDEO,
            SEND_LOCATION,
            RECORD_VOICE,
            CHOOSE_PICTURE -> attachFile(tag.toChoice())

            CANCEL -> fragment.conversation?.let { conversation ->
                if (conversation.setCorrectingMessage(null)) {
                    binding.textinput.setText("")
                    binding.textinput.append(conversation.draftMessage)
                    conversation.draftMessage = null
                } else if (conversation.mode == Conversation.MODE_MULTI) {
                    conversation.nextCounterpart = null
                }
                updateChatMsgHint()
                updateSendButton()
                updateEditablity()
            }

            else -> sendMessage()
        } else sendMessage()
    }
}