package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HideSnackbar @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() {
        fragment.binding!!.snackbar.visibility = View.GONE
    }
}