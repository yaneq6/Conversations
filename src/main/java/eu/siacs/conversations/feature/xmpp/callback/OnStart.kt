package eu.siacs.conversations.feature.xmpp.callback

import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class OnStart @Inject constructor(private val activity: XmppActivity) {
    operator fun invoke(): Unit = activity.run {
        if (!xmppConnectionServiceBound) {
            if (activity.mSkipBackgroundBinding) {
                Timber.d("skipping background binding")
            } else {
                connectToBackend()
            }
        } else {
            activity.registerListeners()
            activity.onBackendConnected()
        }
    }
}