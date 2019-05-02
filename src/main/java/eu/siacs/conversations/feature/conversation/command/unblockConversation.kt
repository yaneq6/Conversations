package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Blockable
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class unblockConversation @Inject constructor(
    private val activity: XmppActivity
): (Blockable?) -> Unit {
    override fun invoke(conversation: Blockable?) {
        activity.xmppConnectionService.sendUnblockRequest(conversation)
    }
}