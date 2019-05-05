package eu.siacs.conversations.feature.xmpp.query

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HasStoragePermission @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(requestCode: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    requestCode
                )
                false
            } else {
                true
            }
        } else {
            true
        }
}