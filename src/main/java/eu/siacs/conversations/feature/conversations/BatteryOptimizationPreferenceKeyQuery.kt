package eu.siacs.conversations.feature.conversations

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.provider.Settings.Secure.ANDROID_ID
import android.provider.Settings.Secure.getString
import javax.inject.Inject

class BatteryOptimizationPreferenceKeyQuery @Inject constructor(
    private val contentResolver: ContentResolver
) : () -> String {

    @SuppressLint("HardwareIds")
    override fun invoke() = getString(contentResolver, ANDROID_ID).let { device ->
        "show_battery_optimization_$device"
    }
}