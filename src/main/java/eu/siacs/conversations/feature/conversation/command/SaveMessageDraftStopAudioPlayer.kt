package eu.siacs.conversations.feature.conversation.command

import android.util.Log
import eu.siacs.conversations.Config
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SaveMessageDraftStopAudioPlayer @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() = fragment.run {
        val previousConversation = this.conversation
        if (this.activity == null || this.binding == null || previousConversation == null) {
            return
        }
        Log.d(
            Config.LOGTAG,
            "ConversationFragment.saveMessageDraftStopAudioPlayer()"
        )
        val msg = this.binding!!.textinput.text!!.toString()
        storeNextMessage(msg)
        updateChatState(this.conversation!!, msg)
        messageListAdapter.stopAudioPlayer()
        mediaPreviewAdapter!!.clearPreviews()
        toggleInputMethod()
    }
}