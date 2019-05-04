package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SetupIme @Inject constructor(
    private val binding: FragmentConversationBinding
) : () -> Unit {
    override fun invoke() {
        binding.textinput.refreshIme()
    }
}