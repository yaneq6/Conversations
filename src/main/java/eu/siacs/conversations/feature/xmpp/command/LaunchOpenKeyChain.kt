package eu.siacs.conversations.feature.xmpp.command

import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class LaunchOpenKeyChain @Inject constructor(
    private val activity: XmppActivity
) {

    operator fun invoke(keyId: Long) {
        val pgp = activity.xmppConnectionService.pgpEngine
        try {
            activity.startIntentSenderForResult(
                pgp!!.getIntentForKey(keyId).intentSender, 0, null, 0,
                0, 0
            )
        } catch (e: Throwable) {
            Toast.makeText(
                activity,
                R.string.openpgp_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}