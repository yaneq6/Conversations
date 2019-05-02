package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.ListViewUtils
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SetSelection @Inject constructor(
    private val fragment: ConversationFragment
) : (Int, Boolean) -> Unit {
    override fun invoke(pos: Int, jumpToBottom: Boolean) = fragment.run {
        ListViewUtils.setSelection(this.binding!!.messagesView, pos, jumpToBottom)
        this.binding!!.messagesView.post {
            ListViewUtils.setSelection(
                this.binding!!.messagesView,
                pos,
                jumpToBottom
            )
        }
        this.binding!!.messagesView.post { this.fireReadEvent() }
        Unit
    }

}