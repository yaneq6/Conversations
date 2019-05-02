package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import android.text.TextUtils
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.utils.UIHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AppendText @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: Activity
) : (String?, Boolean) -> Unit {
    override fun invoke(text: String?, doNotAppend: Boolean) {
        var text: String = text ?: return
        val editable = fragment.binding!!.textinput.text
        val previous = editable?.toString() ?: ""
        if (doNotAppend && !TextUtils.isEmpty(previous)) {
            Toast.makeText(
                activity,
                R.string.already_drafting_message,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (UIHelper.isLastLineQuote(previous)) {
            text = '\n' + text
        } else if (previous.length != 0 && !Character.isWhitespace(previous[previous.length - 1])) {
            text = " " + text
        }
        fragment.binding!!.textinput.append(text)
    }
}