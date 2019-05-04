package eu.siacs.conversations.feature.conversation.callback

import android.view.View
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class JoinMuc @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : View.OnClickListener {
    override fun onClick(view: View) {
        activity.xmppConnectionService.joinMuc(fragment.conversation)
    }
}