package eu.siacs.conversations.feature.xmpp.command

import android.content.Intent
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.ui.EditAccountActivity
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SwitchToAccount @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(account: Account, fingerprint: String) {
        invoke(account, false, fingerprint)
    }

    operator fun invoke(account: Account, init: Boolean = false, fingerprint: String? = null) {
        val intent = Intent(
            activity,
            EditAccountActivity::class.java
        )
        intent.putExtra("jid", account.jid.asBareJid().toString())
        intent.putExtra("init", init)
        if (init) {
            intent.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        if (fingerprint != null) {
            intent.putExtra("fingerprint", fingerprint)
        }
        activity.startActivity(intent)
        if (init) {
            activity.overridePendingTransition(0, 0)
        }
    }
}