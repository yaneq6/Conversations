package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ToggleInputMethod @Inject constructor(
    private val fragment: ConversationFragment
): () -> Unit {
    override fun invoke() = fragment.run {
        val hasAttachments = mediaPreviewAdapter!!.hasAttachments()
        binding!!.textinput.visibility = if (hasAttachments) View.GONE else View.VISIBLE
        binding!!.mediaPreview.visibility = if (hasAttachments) View.VISIBLE else View.GONE
        updateSendButton()
    }
}