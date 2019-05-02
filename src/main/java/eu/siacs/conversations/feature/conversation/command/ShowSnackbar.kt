package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowSnackbar @Inject constructor(
    private val fragment: ConversationFragment
) {
    operator fun invoke(
        message: Int,
        action: Int,
        clickListener: View.OnClickListener?,
        longClickListener: View.OnLongClickListener? = null
    ) = fragment.run {
        this.binding!!.snackbar.visibility = View.VISIBLE
        this.binding!!.snackbar.setOnClickListener(null)
        this.binding!!.snackbarMessage.setText(message)
        this.binding!!.snackbarMessage.setOnClickListener(null)
        this.binding!!.snackbarAction.visibility = if (clickListener == null) View.GONE else View.VISIBLE
        if (action != 0) {
            this.binding!!.snackbarAction.setText(action)
        }
        this.binding!!.snackbarAction.setOnClickListener(clickListener)
        this.binding!!.snackbarAction.setOnLongClickListener(longClickListener)
    }
}