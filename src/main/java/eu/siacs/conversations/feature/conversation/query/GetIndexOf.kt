package eu.siacs.conversations.feature.conversation.query

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class GetIndexOf @Inject constructor(
    val fragment: ConversationFragment
) : (String?, List<Message>) -> Int {
    override fun invoke(uuid: String?, messages: List<Message>): Int = fragment.run {
        if (uuid == null) {
            return messages.size - 1
        }
        for (i in messages.indices) {
            if (uuid == messages[i].uuid) {
                return i
            } else {
                var next: Message? = messages[i]
                while (next != null && next.wasMergedIntoPrevious()) {
                    if (uuid == next.uuid) {
                        return i
                    }
                    next = next.next()
                }

            }
        }
        return -1
    }
}