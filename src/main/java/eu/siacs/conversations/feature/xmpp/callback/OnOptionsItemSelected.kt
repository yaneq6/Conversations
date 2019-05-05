package eu.siacs.conversations.feature.xmpp.callback

import android.content.Intent
import android.view.MenuItem
import eu.siacs.conversations.R
import eu.siacs.conversations.feature.xmpp.command.ShowQrCode
import eu.siacs.conversations.ui.SettingsActivity
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.utils.AccountUtils
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnOptionsItemSelected @Inject constructor(
    private val activity: XmppActivity,
    private val showQrCode: ShowQrCode
) {
    operator fun invoke(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> activity.startActivity(
                Intent(
                    activity,
                    SettingsActivity::class.java
                )
            )
            R.id.action_accounts -> AccountUtils.launchManageAccounts(
                activity
            )
            R.id.action_account -> AccountUtils.launchManageAccount(
                activity
            )
            android.R.id.home -> activity.finish()
            R.id.action_show_qr_code -> showQrCode()
            else -> null
        } != null
    }
}