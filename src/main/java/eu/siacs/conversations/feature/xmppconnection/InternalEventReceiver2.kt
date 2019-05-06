package eu.siacs.conversations.feature.xmppconnection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.siacs.conversations.feature.di.ServiceScope
import javax.inject.Inject

@ServiceScope
class InternalEventReceiver2 @Inject constructor(
    private val onStartCommand: OnStartCommand
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        onStartCommand(intent, 0, 0)
    }
}