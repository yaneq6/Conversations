package eu.siacs.conversations.feature.conversations

import android.net.Uri
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.utils.XmppUri
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HandleXmppUriClickCommand @Inject constructor(
    private val activity: ConversationsActivity,
    private val openConversation: OpenConversationCommand
) : (Uri) -> Boolean {

    override fun invoke(uri: Uri): Boolean {
        val xmppUri = XmppUri(uri)
        if (xmppUri.isJidValid && !xmppUri.hasFingerprints()) {
            val conversation = activity.xmppConnectionService.findUniqueConversationByJid(xmppUri)
            if (conversation != null) {
                openConversation(conversation)
                return true
            }
        }
        return false
    }
}