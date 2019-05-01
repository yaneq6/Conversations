package eu.siacs.conversations.feature.conversation.command

import android.widget.Toast
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HidePrepareFileToast @Inject constructor(
    private val activity: XmppActivity
) : (Toast?) -> Unit {
    override fun invoke(prepareFileToast: Toast?): Unit {
        if (prepareFileToast != null) {
            activity.runOnUiThread { prepareFileToast.cancel() }
        }
    }
}