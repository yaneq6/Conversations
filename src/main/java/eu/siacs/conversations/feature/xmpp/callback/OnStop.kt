package eu.siacs.conversations.feature.xmpp.callback

import eu.siacs.conversations.feature.xmpp.command.UnregisterListeners
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnStop @Inject constructor(
    private val activity: XmppActivity,
    private val unregisterListeners: UnregisterListeners
) {

    operator fun invoke() {
        if (activity.xmppConnectionServiceBound) {
            unregisterListeners()
            activity.unbindService(activity.connection)
            activity.xmppConnectionServiceBound = false
        }
    }
}