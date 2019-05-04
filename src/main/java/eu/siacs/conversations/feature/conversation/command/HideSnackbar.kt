package eu.siacs.conversations.feature.conversation.command

import android.view.View
import eu.siacs.conversations.databinding.FragmentConversationBinding
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HideSnackbar @Inject constructor(
    private val binding: FragmentConversationBinding
) : () -> Unit {
    override fun invoke() {
        binding.snackbar.visibility = View.GONE
    }
}