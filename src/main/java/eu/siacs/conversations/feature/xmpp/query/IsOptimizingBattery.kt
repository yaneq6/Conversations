package eu.siacs.conversations.feature.xmpp.query

import android.content.Context
import android.os.Build
import android.os.PowerManager
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class IsOptimizingBattery @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm != null && !pm.isIgnoringBatteryOptimizations(activity.packageName)
        } else {
            false
        }
}