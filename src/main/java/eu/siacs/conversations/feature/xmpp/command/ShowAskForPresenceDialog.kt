package eu.siacs.conversations.feature.xmpp.command

import android.support.v7.app.AlertDialog
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowAskForPresenceDialog @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(contact: Contact) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(contact.jid.toString())
        builder.setMessage(R.string.request_presence_updates)
        builder.setNegativeButton(R.string.cancel, null)
        builder.setPositiveButton(
            R.string.request_now
        ) { dialog, which ->
            if (activity.xmppConnectionServiceBound) {
                activity.xmppConnectionService.sendPresencePacket(
                    contact.account,
                    activity.xmppConnectionService
                        .presenceGenerator
                        .requestPresenceUpdatesFrom(contact)
                )
            }
        }
        builder.create().show()
    }
}