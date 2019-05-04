package eu.siacs.conversations.feature.conversation.query

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.util.ScrollState
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class GetScrollPosition @Inject constructor(
    private val binding: FragmentConversationBinding
): () -> ScrollState? {
    override fun invoke(): ScrollState? {

        val listView = binding.messagesView
        return if (listView.count == 0 || listView.lastVisiblePosition == listView.count - 1) {
            null
        } else {
            val pos = listView.firstVisiblePosition
            val view = listView.getChildAt(0)
            if (view == null) {
                null
            } else {
                ScrollState(pos, view.top)
            }
        }
    }
}