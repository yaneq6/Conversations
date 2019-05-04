package eu.siacs.conversations.feature.conversation.callback

import android.view.View
import eu.siacs.conversations.feature.conversation.command.HideSnackbar
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AllowPresenceSubscription @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val hideSnackbar: HideSnackbar
) : View.OnClickListener {

    override fun onClick(view: View) {
        fragment.conversation?.contact?.let { contact ->
            activity.xmppConnectionService.sendPresencePacket(
                contact.account,
                activity.xmppConnectionService.presenceGenerator
                    .sendPresenceUpdatesTo(contact)
            )
            hideSnackbar()
        }
    }
}