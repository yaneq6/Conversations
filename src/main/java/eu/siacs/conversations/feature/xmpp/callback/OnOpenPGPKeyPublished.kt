package eu.siacs.conversations.feature.xmpp.callback

import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnOpenPGPKeyPublished @Inject constructor(
    private val activity: XmppActivity
): Runnable {

    override fun run() {
        Toast.makeText(
            activity,
            R.string.openpgp_has_been_published,
            Toast.LENGTH_SHORT
        ).show()
    }
}