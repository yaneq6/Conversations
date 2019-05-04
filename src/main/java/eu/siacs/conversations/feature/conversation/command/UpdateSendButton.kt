package eu.siacs.conversations.feature.conversation.command

import android.content.res.Resources
import android.preference.PreferenceManager
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Presence
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.SendButtonAction
import eu.siacs.conversations.ui.util.SendButtonTool
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UpdateSendButton @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity,
    private val binding: FragmentConversationBinding,
    private val resources: Resources
) : () -> Unit {
    override fun invoke() {
        val mediaPreviewAdapter = fragment.mediaPreviewAdapter
        val hasAttachments = mediaPreviewAdapter != null && mediaPreviewAdapter.hasAttachments()
        val useSendButtonToIndicateStatus = PreferenceManager.getDefaultSharedPreferences(activity)
            .getBoolean("send_button_status", resources.getBoolean(R.bool.send_button_status))
        val conversation = fragment.conversation!!
        val text = binding.textinput.text?.toString() ?: ""
        val action: SendButtonAction =
            if (hasAttachments) {
                SendButtonAction.TEXT
            } else {
                SendButtonTool.getAction(activity, conversation, text)
            }
        val status: Presence.Status =
            if (useSendButtonToIndicateStatus && conversation.account.status == Account.State.ONLINE) {
                if (activity.xmppConnectionService != null
                    && activity.xmppConnectionService.messageArchiveService.isCatchingUp(conversation)
                ) {
                    Presence.Status.OFFLINE
                } else if (conversation.mode == Conversation.MODE_SINGLE) {
                    conversation.contact.shownStatus
                } else {
                    if (conversation.mucOptions.online()) Presence.Status.ONLINE else Presence.Status.OFFLINE
                }
            } else {
                Presence.Status.OFFLINE
            }
        binding.textSendButton.tag = action
        binding.textSendButton.setImageResource(
            SendButtonTool.getSendButtonImageResource(
                activity,
                action,
                status
            )
        )
    }
}