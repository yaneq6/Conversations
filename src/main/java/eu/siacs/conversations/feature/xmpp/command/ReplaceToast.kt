package eu.siacs.conversations.feature.xmpp.command

import android.widget.Toast
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ReplaceToast @Inject constructor(
    private val activity: XmppActivity,
    private val hideToast: HideToast
) {
    operator fun invoke(msg: String) {
        invoke(msg, true)
    }

    operator fun invoke(msg: String, showlong: Boolean) {
        hideToast()
        activity.mToast = Toast.makeText(
            activity,
            msg,
            if (showlong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).apply {
            show()
        }
    }
}