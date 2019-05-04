package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import java.util.ArrayList
import javax.inject.Inject

@ActivityScope
class OnTabPressed @Inject constructor(
    private val fragment: ConversationFragment
) {
    operator fun invoke(repeated: Boolean): Boolean {
        val conversation = fragment.conversation
        if (conversation == null || conversation.mode == Conversation.MODE_SINGLE) {
            return false
        }
        fragment.run {
            if (repeated) {
                completionIndex++
            } else {
                lastCompletionLength = 0
                completionIndex = 0
                val content = this.binding!!.textinput.text!!.toString()
                lastCompletionCursor = this.binding!!.textinput.selectionEnd
                val start = if (lastCompletionCursor > 0) content.lastIndexOf(
                    " ",
                    lastCompletionCursor - 1
                ) + 1 else 0
                firstWord = start == 0
                incomplete = content.substring(start, lastCompletionCursor)
            }
            val completions = ArrayList<String>()
            for (user in conversation.mucOptions.users) {
                val name = user.name
                if (name != null && name.startsWith(incomplete!!)) {
                    completions.add(name + if (firstWord) ": " else " ")
                }
            }
            completions.sort()
            if (completions.size > completionIndex) {
                val completion = completions[completionIndex].substring(incomplete!!.length)
                this.binding!!.textinput.editableText.delete(
                    lastCompletionCursor,
                    lastCompletionCursor + lastCompletionLength
                )
                this.binding!!.textinput.editableText.insert(lastCompletionCursor, completion)
                lastCompletionLength = completion.length
            } else {
                completionIndex = -1
                this.binding!!.textinput.editableText.delete(
                    lastCompletionCursor,
                    lastCompletionCursor + lastCompletionLength
                )
                lastCompletionLength = 0
            }
            return true
        }
    }
}