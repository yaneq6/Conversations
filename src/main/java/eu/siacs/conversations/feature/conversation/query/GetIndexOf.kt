package eu.siacs.conversations.feature.conversation.query

import eu.siacs.conversations.entities.Message
import javax.inject.Inject

class GetIndexOf @Inject constructor() : (String?, List<Message>) -> Int {
    override fun invoke(uuid: String?, messages: List<Message>): Int {
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