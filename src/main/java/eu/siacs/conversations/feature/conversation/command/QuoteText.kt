package eu.siacs.conversations.feature.conversation.command

import android.content.Context
import android.view.inputmethod.InputMethodManager
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class QuoteText @Inject constructor(
    private val activity: XmppActivity,
    private val binding: FragmentConversationBinding
) : (String) -> Unit {
    override fun invoke(text: String) = binding.textinput.run {
        if (isEnabled) {
            insertAsQuote(text)
            requestFocus()
            activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                ?.let { it as? InputMethodManager }
                ?.showSoftInput(
                    this,
                    InputMethodManager.SHOW_IMPLICIT
                )
        }
    }
}