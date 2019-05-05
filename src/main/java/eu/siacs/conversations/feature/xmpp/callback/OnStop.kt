package eu.siacs.conversations.feature.xmpp.callback

import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnStop @Inject constructor(private val activity: XmppActivity) {

    operator fun invoke(): Unit = activity.run {
        if (xmppConnectionServiceBound) {
            activity.unregisterListeners()
            unbindService(connection)
            xmppConnectionServiceBound = false
        }
    }
}