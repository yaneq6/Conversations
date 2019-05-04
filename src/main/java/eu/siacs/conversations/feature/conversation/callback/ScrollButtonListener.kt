package eu.siacs.conversations.feature.conversation.callback

import android.view.View
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.feature.conversation.command.SetSelection
import eu.siacs.conversations.feature.conversation.command.StopScrolling
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ScrollButtonListener @Inject constructor(
    private val binding: FragmentConversationBinding,
    private val stopScrolling: StopScrolling,
    private val setSelection: SetSelection
) : View.OnClickListener {

    override fun onClick(view: View) {
        stopScrolling()
        setSelection(binding.messagesView.count - 1, true)
    }
}