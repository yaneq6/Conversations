package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.databinding.FragmentConversationBinding
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowSnackbar @Inject constructor(
    private val binding: FragmentConversationBinding
) {
    operator fun invoke(
        message: Int,
        action: Int,
        clickListener: View.OnClickListener?,
        longClickListener: View.OnLongClickListener? = null
    ): Unit = binding.run {
        snackbar.visibility = View.VISIBLE
        snackbar.setOnClickListener(null)
        snackbarMessage.setText(message)
        snackbarMessage.setOnClickListener(null)
        snackbarAction.visibility = if (clickListener == null) View.GONE else View.VISIBLE
        if (action != 0) {
            snackbarAction.setText(action)
        }
        snackbarAction.setOnClickListener(clickListener)
        snackbarAction.setOnLongClickListener(longClickListener)
    }
}