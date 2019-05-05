package eu.siacs.conversations.feature.xmpp.command

import android.support.v7.app.AlertDialog
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowAddToRosterDialog @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(contact: Contact) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(contact.jid.toString())
        builder.setMessage(activity.getString(R.string.not_in_roster))
        builder.setNegativeButton(activity.getString(R.string.cancel), null)
        builder.setPositiveButton(activity.getString(R.string.add_contact)) { dialog, which ->
            activity.xmppConnectionService.createContact(
                contact,
                true
            )
        }
        builder.create().show()
    }
}