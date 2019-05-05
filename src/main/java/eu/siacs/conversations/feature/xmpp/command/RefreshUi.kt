package eu.siacs.conversations.feature.xmpp.command

import android.os.SystemClock
import eu.siacs.conversations.Config
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class RefreshUi @Inject constructor(private val activity: XmppActivity) {
    operator fun invoke(): Unit = activity.run {
        val diff = SystemClock.elapsedRealtime() - mLastUiRefresh
        if (diff > Config.REFRESH_UI_INTERVAL) {
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable)
            runOnUiThread(mRefreshUiRunnable)
        } else {
            val next = Config.REFRESH_UI_INTERVAL - diff
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable)
            mRefreshUiHandler.postDelayed(mRefreshUiRunnable, next)
        }
    }
}