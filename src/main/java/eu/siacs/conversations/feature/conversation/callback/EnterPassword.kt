package eu.siacs.conversations.feature.conversation.callback

import android.view.View
import eu.siacs.conversations.feature.xmpp.command.QuickPasswordEdit
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class EnterPassword @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val quickPasswordEdit: QuickPasswordEdit
) : View.OnClickListener {
    override fun onClick(view: View) {
        val conversation = fragment.conversation!!
        val muc = conversation.mucOptions
        var password: String? = muc.password
        if (password == null) {
            password = ""
        }
        quickPasswordEdit(password) { value ->
            activity.xmppConnectionService.providePasswordForMuc(conversation, value)
            null
        }
    }
}