package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HideUnreadMessagesCount @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() {
        fragment.binding?.run {
            scrollToBottomButton.isEnabled = false
            scrollToBottomButton.hide()
            unreadCountCustomView.visibility = View.GONE
        }
    }
}