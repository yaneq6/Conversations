package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.ScrollState
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SetScrollPosition @Inject constructor(
    private val fragment: ConversationFragment,
    private val toggleScrollDownButton: ToggleScrollDownButton
) : (ScrollState?, String?) -> Unit {
    override fun invoke(scrollPosition: ScrollState?, lastMessageUuid: String?): Unit = fragment.run {
        if (scrollPosition != null) {

            this.lastMessageUuid = lastMessageUuid
            if (lastMessageUuid != null) {
                binding!!.unreadCountCustomView.setUnreadCount(
                    conversation!!.getReceivedMessagesCountSinceUuid(
                        lastMessageUuid
                    )
                )
            }
            //TODO maybe this needs a 'post'
            binding!!.messagesView.setSelectionFromTop(scrollPosition.position, scrollPosition.offset)
            this@SetScrollPosition.toggleScrollDownButton()
        }
    }
}