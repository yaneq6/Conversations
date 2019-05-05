package eu.siacs.conversations.feature.xmpp.callback

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import eu.siacs.conversations.feature.conversation.command.OnBackendConnected
import eu.siacs.conversations.feature.xmpp.command.RegisterListeners
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class Connection @Inject constructor(
    private val activity: XmppActivity,
    private val registerListeners: RegisterListeners,
    private val onBackendConnected: OnBackendConnected
): ServiceConnection {

    override fun onServiceConnected(className: ComponentName, service: IBinder) {
        val binder = service as XmppConnectionService.XmppConnectionBinder
        activity.apply {
            xmppConnectionService = binder.service
            xmppConnectionServiceBound = true
        }
        registerListeners()
        onBackendConnected()
    }

    override fun onServiceDisconnected(arg0: ComponentName) {
        activity.xmppConnectionServiceBound = false
    }
}