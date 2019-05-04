package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class SaveMessageDraftStopAudioPlayer @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val storeNextMessage: StoreNextMessage,
    private val updateChatState: UpdateChatState,
    private val toggleInputMethod: ToggleInputMethod
) : () -> Unit {
    override fun invoke() {
        fragment.conversation ?: return
        Timber.d("ConversationFragment.saveMessageDraftStopAudioPlayer()")
        val msg = binding.textinput.text!!.toString()
        storeNextMessage(msg)
        updateChatState(fragment.conversation!!, msg)
        fragment.messageListAdapter.stopAudioPlayer()
        fragment.mediaPreviewAdapter!!.clearPreviews()
        toggleInputMethod()
    }
}