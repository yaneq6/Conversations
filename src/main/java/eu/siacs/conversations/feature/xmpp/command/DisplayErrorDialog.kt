package eu.siacs.conversations.feature.xmpp.command

import android.support.v7.app.AlertDialog
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class DisplayErrorDialog @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(errorCode: Int) {
        activity.runOnUiThread {
            val builder = AlertDialog.Builder(activity)
            builder.setIconAttribute(android.R.attr.alertDialogIcon)
            builder.setTitle(activity.getString(R.string.error))
            builder.setMessage(errorCode)
            builder.setNeutralButton(R.string.accept, null)
            builder.create().show()
        }

    }
}