package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.utils.NickValidityChecker
import io.aakit.scope.ActivityScope
import java.util.*
import javax.inject.Inject

@ActivityScope
class HighlightInConference @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding
) : (String) -> Unit {
    override fun invoke(nick: String) {
        val conversation = fragment.conversation!!
        val editable = binding.textinput.text!!
        val oldString = editable.toString().trim { it <= ' ' }
        val pos = binding.textinput.selectionStart

        if (oldString.isEmpty() || pos == 0) {
            editable.insert(0, "$nick: ")
        } else {
            val before = editable[pos - 1]
            val after = if (editable.length > pos) editable[pos] else '\u0000'
            if (before == '\n') {
                editable.insert(pos, "$nick: ")
            } else {
                if (pos > 2 && editable.subSequence(pos - 2, pos).toString() == ": ") {
                    if (NickValidityChecker.check(
                            conversation,
                            Arrays.asList(
                                *editable.subSequence(
                                    0,
                                    pos - 2
                                ).toString().split(", ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                            )
                        )
                    ) {
                        editable.insert(pos - 2, ", $nick")
                        return
                    }
                }
                editable.insert(
                    pos,
                    (if (Character.isWhitespace(before)) "" else " ") + nick + if (Character.isWhitespace(after)) "" else " "
                )
                if (Character.isWhitespace(after)) {
                    binding.textinput.setSelection(binding.textinput.selectionStart + 1)
                }
            }
        }
        Unit
    }
}