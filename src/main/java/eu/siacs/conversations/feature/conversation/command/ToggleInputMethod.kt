package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ToggleInputMethod @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val updateSendButton: UpdateSendButton
): () -> Unit {

    override fun invoke() {
        binding.setVisibility(fragment.mediaPreviewAdapter!!.hasAttachments())
        updateSendButton()
    }

    private fun FragmentConversationBinding.setVisibility(hasAttachments: Boolean) {
        textinput.visibility = if (hasAttachments) View.GONE else View.VISIBLE
        mediaPreview.visibility = if (hasAttachments) View.VISIBLE else View.GONE
    }
}