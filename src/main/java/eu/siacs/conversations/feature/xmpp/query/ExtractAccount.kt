package eu.siacs.conversations.feature.xmpp.query

import android.content.Intent
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.feature.xmpp.XmppConst
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import rocks.xmpp.addr.Jid
import javax.inject.Inject

@ActivityScope
class ExtractAccount @Inject constructor(
    private val activity: XmppActivity
) {

    operator fun invoke(intent: Intent?): Account? {
        val jid = intent?.getStringExtra(XmppConst.EXTRA_ACCOUNT)
        return try {
            if (jid != null) activity.xmppConnectionService.findAccountByJid(
                Jid.of(
                    jid
                )
            ) else null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}