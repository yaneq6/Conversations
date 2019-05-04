package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.ScrollState
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SetScrollPosition @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val toggleScrollDownButton: ToggleScrollDownButton
) : (ScrollState?, String?) -> Unit {
    override fun invoke(scrollPosition: ScrollState?, lastMessageUuid: String?) {
        if (scrollPosition != null) {

            fragment.lastMessageUuid = lastMessageUuid
            if (lastMessageUuid != null) binding.unreadCountCustomView.setUnreadCount(
                fragment.conversation!!.getReceivedMessagesCountSinceUuid(lastMessageUuid)
            )
            //TODO maybe this needs a 'post'
            binding.messagesView.setSelectionFromTop(scrollPosition.position, scrollPosition.offset)
            this@SetScrollPosition.toggleScrollDownButton()
        }
    }
}