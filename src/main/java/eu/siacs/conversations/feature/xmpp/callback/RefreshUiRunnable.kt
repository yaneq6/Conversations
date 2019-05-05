package eu.siacs.conversations.feature.xmpp.callback

import android.os.SystemClock
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class RefreshUiRunnable @Inject constructor(
    private val activity: XmppActivity,
    private val refreshUiReal: RefreshUiRunnable
): () -> Unit {

    override fun invoke() {
        activity.mLastUiRefresh = SystemClock.elapsedRealtime()
        refreshUiReal()
    }
}