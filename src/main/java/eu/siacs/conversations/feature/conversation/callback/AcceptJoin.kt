package eu.siacs.conversations.feature.conversation.callback

import android.view.View
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AcceptJoin @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : View.OnClickListener {
    override fun onClick(view: View) {
        val conversation = fragment.conversation!!
        conversation.setAttribute("accept_non_anonymous", true)
        activity.xmppConnectionService.updateConversation(conversation)
        activity.xmppConnectionService.joinMuc(conversation)
    }
}