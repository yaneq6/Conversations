package eu.siacs.conversations.feature.conversations

import android.app.Activity
import android.app.FragmentManager
import android.view.MenuItem
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.UriHandlerActivity
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class HandleOptionsItemSelected @Inject constructor(
    private val activity: Activity,
    private val fragmentManager: FragmentManager
) : (MenuItem) -> Boolean? {

    override fun invoke(item: MenuItem): Boolean? =
        if (MenuDoubleTabUtil.shouldIgnoreTap()) false
        else when (item.itemId) {

            android.R.id.home -> fragmentManager
                .takeIf { it.backStackEntryCount > 0 }?.run {
                    try {
                        popBackStack()
                    } catch (e: IllegalStateException) {
                        Timber.w("Unable to pop back stack after pressing home button")
                    }
                } != null

            R.id.action_scan_qr_code -> {
                UriHandlerActivity.scan(activity)
                true
            }

            else -> null
        }

}