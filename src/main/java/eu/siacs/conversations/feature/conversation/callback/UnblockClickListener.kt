package eu.siacs.conversations.feature.conversation.callback

import android.view.View
import eu.siacs.conversations.feature.conversation.command.UnblockConversation
import eu.siacs.conversations.ui.BlockContactDialog
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UnblockClickListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val unblockConversation: UnblockConversation
) : View.OnClickListener {
    override fun onClick(view: View) {
        view.post { view.visibility = View.INVISIBLE }
        val conversation = fragment.conversation!!
        if (conversation.isDomainBlocked) {
            BlockContactDialog.show(activity, conversation)
        } else {
            unblockConversation(conversation)
        }
    }
}