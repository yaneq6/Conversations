package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class CorrectMessage @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding
) : (Message) -> Unit {
    override fun invoke(message: Message) {
        val conversation = fragment.conversation!!

        var message = message
        while (message.mergeable(message.next())) {
            message = message.next()
        }

        conversation.correctingMessage = message
        val editable = binding.textinput.text
        conversation.draftMessage = editable!!.toString()
        binding.textinput.setText("")
        binding.textinput.append(message.body)
    }
}