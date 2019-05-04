package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnResume @Inject constructor(
    private val binding: FragmentConversationBinding,
    private val fireReadEvent: FireReadEvent
) {
    operator fun invoke() {
        binding.messagesView.post(fireReadEvent)
    }
}