package eu.siacs.conversations.feature.conversations

import android.view.Menu
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.ConversationsOverviewFragment
import eu.siacs.conversations.utils.AccountUtils

class CreateOptionMenuCommand(
    private val activity: ConversationsActivity
) : (Menu) -> Unit {
    override fun invoke(menu: Menu) = activity.run {
        menuInflater.inflate(R.menu.activity_conversations, menu)
        AccountUtils.showHideMenuItems(menu)
        val qrCodeScanMenuItem = menu.findItem(R.id.action_scan_qr_code)
        if (qrCodeScanMenuItem != null) {
            if (isCameraFeatureAvailable) {
                val fragment = fragmentManager.findFragmentById(R.id.main_fragment)
                val visible = (resources.getBoolean(R.bool.show_qr_code_scan)
                        && fragment != null
                        && fragment is ConversationsOverviewFragment)
                qrCodeScanMenuItem.isVisible = visible
            } else {
                qrCodeScanMenuItem.isVisible = false
            }
        }
    }
}