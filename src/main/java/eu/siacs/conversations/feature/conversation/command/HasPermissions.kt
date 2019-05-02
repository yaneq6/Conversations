package eu.siacs.conversations.feature.conversation.command

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import eu.siacs.conversations.Config
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import java.util.*
import javax.inject.Inject

@ActivityScope
class HasPermissions @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: Activity
) {
    operator fun invoke(requestCode: Int, vararg permissions: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val missingPermissions = ArrayList<String>()
            for (permission in permissions) {
                if (Config.ONLY_INTERNAL_STORAGE && permission == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
                    continue
                }
                if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    missingPermissions.add(permission)
                }
            }
            if (missingPermissions.size == 0)
                true
            else {
                fragment.requestPermissions(missingPermissions.toTypedArray(), requestCode)
                false
            }
        } else
            true
    }
}