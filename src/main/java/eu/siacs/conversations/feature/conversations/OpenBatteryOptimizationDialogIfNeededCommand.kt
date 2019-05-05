package eu.siacs.conversations.feature.conversations

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.feature.xmpp.query.IsOptimizingBattery
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.XmppActivity.Companion.REQUEST_BATTERY_OP
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OpenBatteryOptimizationDialogIfNeededCommand @Inject constructor(
    private val activity: ConversationsActivity,
    private val hasAccountWithoutPush: HasAccountWithoutPushQuery,
    private val batteryOptimizationPreferenceKey: BatteryOptimizationPreferenceKeyQuery,
    private val preferences: SharedPreferences,
    private val isOptimizingBattery: IsOptimizingBattery
) : () -> Unit {

    @SuppressLint("BatteryLife")
    override fun invoke() {
        if (hasAccountWithoutPush()
            && isOptimizingBattery()
            && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
            && preferences.getBoolean(batteryOptimizationPreferenceKey(), true)
        ) {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle(R.string.battery_optimizations_enabled)
            builder.setMessage(R.string.battery_optimizations_enabled_dialog)
            builder.setPositiveButton(R.string.next) { dialog, which ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                val uri = Uri.parse("package:${activity.packageName}")
                intent.data = uri
                try {
                    activity.startActivityForResult(intent, REQUEST_BATTERY_OP)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(activity, R.string.device_does_not_support_battery_op, Toast.LENGTH_SHORT).show()
                }
            }
            builder.setOnDismissListener { dialog -> activity.setNeverAskForBatteryOptimizationsAgain() }
            val dialog = builder.create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
        }
    }
}