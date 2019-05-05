package eu.siacs.conversations.feature.xmpp.callback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.feature.conversation.command.OnBackendConnected
import eu.siacs.conversations.feature.xmpp.command.HideToast
import eu.siacs.conversations.feature.xmpp.command.RegisterListeners
import eu.siacs.conversations.feature.xmpp.command.ReplaceToast
import eu.siacs.conversations.feature.xmpp.command.SwitchToConversation
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnOpenPGPKeyPublished @Inject constructor(
    private val activity: XmppActivity
): Runnable {
    override fun run() {
        Toast.makeText(activity, R.string.openpgp_has_been_published, Toast.LENGTH_SHORT).show()
    }

}
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

@ActivityScope
class RefreshUiRunnable @Inject constructor(
    private val activity: XmppActivity,
    private val refreshUiReal: RefreshUiRunnable
): () -> Unit {

    override fun invoke() {
        activity.mLastUiRefresh = SystemClock.elapsedRealtime()
        refreshUiReal()
    }

}

@ActivityScope
class AdhocCallback @Inject constructor(
    private val activity: XmppActivity,
    private val switchToConversation: SwitchToConversation,
    private val hideToast: HideToast,
    private val replaceToast: ReplaceToast
): UiCallback<Conversation> {

    override fun success(conversation: Conversation) {
        activity.runOnUiThread {
            switchToConversation(conversation)
            hideToast()
        }
    }

    override fun error(errorCode: Int, conversation: Conversation) {
        activity.runOnUiThread { replaceToast(activity.getString(errorCode)) }
    }

    override fun userInputRequried(intent: PendingIntent, conversation: Conversation) {

    }
}