package eu.siacs.conversations.feature.xmpp.callback

import android.app.PendingIntent
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.feature.xmpp.command.HideToast
import eu.siacs.conversations.feature.xmpp.command.ReplaceToast
import eu.siacs.conversations.feature.xmpp.command.SwitchToConversation
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AdhocCallback @Inject constructor(
    private val activity: XmppActivity,
    private val switchToConversation: SwitchToConversation,
    private val hideToast: HideToast,
    private val replaceToast: ReplaceToast
): UiCallback<Conversation> {

    override fun success(conversation: Conversation) {
        activity.runOnUiThread {
            switchToConversation(conversation)
            hideToast()
        }
    }

    override fun error(errorCode: Int, conversation: Conversation) {
        activity.runOnUiThread { replaceToast(activity.getString(errorCode)) }
    }

    override fun userInputRequried(intent: PendingIntent, conversation: Conversation) {

    }
}