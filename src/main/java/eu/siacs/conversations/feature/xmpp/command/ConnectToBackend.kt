package eu.siacs.conversations.feature.xmpp.command

import android.content.Context
import android.content.Intent
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class ConnectToBackend @Inject constructor(private val activity: XmppActivity) {

    operator fun invoke(): Unit = activity.run {
        val intent = Intent(
            activity,
            XmppConnectionService::class.java
        )
        intent.action = "ui"
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            Timber.w("unable to start service from " + javaClass.simpleName)
        }

        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
}