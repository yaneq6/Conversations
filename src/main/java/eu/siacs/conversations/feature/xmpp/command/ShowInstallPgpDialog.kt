package eu.siacs.conversations.feature.xmpp.command

import android.content.Intent
import android.net.Uri
import android.support.v7.app.AlertDialog
import eu.siacs.conversations.R
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowInstallPgpDialog @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(): Unit = activity.run {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(getString(R.string.openkeychain_required))
        builder.setIconAttribute(android.R.attr.alertDialogIcon)
        builder.setMessage(getText(R.string.openkeychain_required_long))
        builder.setNegativeButton(getString(R.string.cancel), null)
        builder.setNeutralButton(
            getString(R.string.restart)
        ) { dialog, which ->
            if (xmppConnectionServiceBound) {
                unbindService(mConnection)
                xmppConnectionServiceBound = false
            }
            stopService(
                Intent(
                    activity,
                    XmppConnectionService::class.java
                )
            )
            finish()
        }
        builder.setPositiveButton(
            getString(R.string.install)
        ) { dialog, which ->
            var uri = Uri.parse("market://details?id=org.sufficientlysecure.keychain")
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                uri
            )
            val manager = applicationContext.packageManager
            val infos = manager.queryIntentActivities(marketIntent, 0)
            if (infos.size > 0) {
                startActivity(marketIntent)
            } else {
                uri = Uri.parse("http://www.openkeychain.org/")
                val browserIntent = Intent(
                    Intent.ACTION_VIEW, uri
                )
                startActivity(browserIntent)
            }
            finish()
        }
        builder.create().show()
    }
}