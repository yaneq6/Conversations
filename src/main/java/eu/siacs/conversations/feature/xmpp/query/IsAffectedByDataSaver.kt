package eu.siacs.conversations.feature.xmpp.query

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class IsAffectedByDataSaver @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            (cm != null && cm.isActiveNetworkMetered && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        } else {
            false
        }
}