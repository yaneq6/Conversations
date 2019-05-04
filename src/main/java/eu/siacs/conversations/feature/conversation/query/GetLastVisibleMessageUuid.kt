package eu.siacs.conversations.feature.conversation.query

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

//should not happen if we synchronize properly. however if that fails we just gonna try item -1
@ActivityScope
class GetLastVisibleMessageUuid @Inject constructor(
    private val fragment: ConversationFragment
): () -> String? {

    override fun invoke(): String? {
        val binding = fragment.binding ?: return null

        synchronized(fragment.messageList) {
            val pos = binding.messagesView.lastVisiblePosition
            if (pos >= 0) {
                var message: Message? = null
                for (i in pos downTo 0) {
                    try {
                        message = binding.messagesView.getItemAtPosition(i) as Message
                    } catch (e: IndexOutOfBoundsException) {
                        continue
                    }

                    if (message.type != Message.TYPE_STATUS) {
                        break
                    }
                }
                if (message != null) {
                    while (message!!.next() != null && message.next()!!.wasMergedIntoPrevious()) {
                        message = message.next()
                    }
                    return message.uuid
                }
            }
        }
        return null
    }
}