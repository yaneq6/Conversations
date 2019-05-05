package eu.siacs.conversations.feature.xmpp.callback

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.feature.xmpp.ConferenceInvite
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnActivityResult @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == XmppActivity.REQUEST_INVITE_TO_CONVERSATION && resultCode == Activity.RESULT_OK) {
            activity.mPendingConferenceInvite =
                ConferenceInvite.parse(data!!)
            if (activity.xmppConnectionServiceBound && activity.mPendingConferenceInvite != null) {
                if (activity.mPendingConferenceInvite!!.execute(activity)) {
                    activity.mToast =
                        Toast.makeText(
                            activity,
                            R.string.creating_conference,
                            Toast.LENGTH_LONG
                        )
                    activity.mToast!!.show()
                }
                activity.mPendingConferenceInvite = null
            }
        }
    }
}