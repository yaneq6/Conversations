package eu.siacs.conversations.feature.conversation.command

import android.preference.PreferenceManager
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Presence
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.SendButtonAction
import eu.siacs.conversations.ui.util.SendButtonTool
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UpdateSendButton @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() = fragment.run {
        val hasAttachments = mediaPreviewAdapter != null && mediaPreviewAdapter!!.hasAttachments()
        val useSendButtonToIndicateStatus = PreferenceManager.getDefaultSharedPreferences(getActivity())
            .getBoolean("send_button_status", resources.getBoolean(R.bool.send_button_status))
        val c = this.conversation
        val status: Presence.Status
        val text = if (this.binding!!.textinput == null) "" else this.binding!!.textinput.text!!.toString()
        val action: SendButtonAction
        if (hasAttachments) {
            action = SendButtonAction.TEXT
        } else {
            action = SendButtonTool.getAction(getActivity(), c!!, text)
        }
        if (useSendButtonToIndicateStatus && c!!.account.status == Account.State.ONLINE) {
            if (activity!!.xmppConnectionService != null && activity!!.xmppConnectionService.messageArchiveService.isCatchingUp(
                    c
                )
            ) {
                status = Presence.Status.OFFLINE
            } else if (c.mode == Conversation.MODE_SINGLE) {
                status = c.contact.shownStatus
            } else {
                status = if (c.mucOptions.online()) Presence.Status.ONLINE else Presence.Status.OFFLINE
            }
        } else {
            status = Presence.Status.OFFLINE
        }
        this.binding!!.textSendButton.tag = action
        this.binding!!.textSendButton.setImageResource(
            SendButtonTool.getSendButtonImageResource(
                getActivity(),
                action,
                status
            )
        )
    }
}