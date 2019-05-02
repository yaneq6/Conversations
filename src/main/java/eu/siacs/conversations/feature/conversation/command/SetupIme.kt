package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SetupIme @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() = fragment.run {
        this.binding!!.textinput.refreshIme()
    }
}