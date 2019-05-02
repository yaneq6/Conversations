package eu.siacs.conversations.feature.conversation.command

import android.content.Context
import android.view.inputmethod.InputMethodManager
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class QuoteText @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity
) : (String) -> Unit {
    override fun invoke(text: String) = fragment.binding!!.textinput.run {
        if (isEnabled) {
            insertAsQuote(text)
            requestFocus()
            activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                ?.let { it as? InputMethodManager }
                ?.showSoftInput(
                    fragment.binding!!.textinput,
                    InputMethodManager.SHOW_IMPLICIT
                )
        }
    }
}