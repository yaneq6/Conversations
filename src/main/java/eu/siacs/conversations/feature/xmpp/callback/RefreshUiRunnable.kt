package eu.siacs.conversations.feature.xmpp.callback

import android.os.SystemClock
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class RefreshUiRunnable @Inject constructor(
    private val activity: XmppActivity
): () -> Unit {

    override fun invoke() {
        activity.run {
            mLastUiRefresh = SystemClock.elapsedRealtime()
            refreshUiReal()
        }
    }
}