package eu.siacs.conversations.feature.xmpp.command

import android.app.Activity
import android.content.Intent
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.feature.xmpp.XmppConst
import eu.siacs.conversations.ui.ContactDetailsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SwitchToContactDetails @Inject constructor(
    private val activity: Activity
) {
    operator fun invoke(contact: Contact, messageFingerprint: String? = null) {
        val intent = Intent(
            activity,
            ContactDetailsActivity::class.java
        )
        intent.action = ContactDetailsActivity.ACTION_VIEW_CONTACT
        intent.putExtra(XmppConst.EXTRA_ACCOUNT, contact.account.jid.asBareJid().toString())
        intent.putExtra("contact", contact.jid.toString())
        intent.putExtra("fingerprint", messageFingerprint)
        activity.startActivity(intent)
    }
}