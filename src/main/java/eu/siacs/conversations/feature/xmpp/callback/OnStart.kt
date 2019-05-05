package eu.siacs.conversations.feature.xmpp.callback

import eu.siacs.conversations.feature.xmpp.command.ConnectToBackend
import eu.siacs.conversations.feature.xmpp.command.RegisterListeners
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class OnStart @Inject constructor(
    private val activity: XmppActivity,
    private val connectToBackend: ConnectToBackend,
    private val registerListeners: RegisterListeners
) {
    operator fun invoke() {
        if (!activity.xmppConnectionServiceBound) {
            if (activity.mSkipBackgroundBinding) {
                Timber.d("skipping background binding")
            } else {
                connectToBackend()
            }
        } else {
            registerListeners()
            activity.onBackendConnected()
        }
    }
}