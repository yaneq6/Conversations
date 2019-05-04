package eu.siacs.conversations.feature.conversation.callback

import android.view.View
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class EnableAccountListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : View.OnClickListener {
    override fun onClick(view: View) {
        val account = fragment.conversation?.account
        if (account != null) {
            account.setOption(Account.OPTION_DISABLED, false)
            activity.xmppConnectionService.updateAccount(account)
        }
    }
}