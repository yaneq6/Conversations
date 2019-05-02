package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UpdateEditablity @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() = fragment.run {
        val canWrite =
            this.conversation!!.mode == Conversation.MODE_SINGLE || this.conversation!!.mucOptions.participating() || this.conversation!!.nextCounterpart != null
        this.binding!!.textinput.isFocusable = canWrite
        this.binding!!.textinput.isFocusableInTouchMode = canWrite
        this.binding!!.textSendButton.isEnabled = canWrite
        this.binding!!.textinput.isCursorVisible = canWrite
        this.binding!!.textinput.isEnabled = canWrite
    }
}