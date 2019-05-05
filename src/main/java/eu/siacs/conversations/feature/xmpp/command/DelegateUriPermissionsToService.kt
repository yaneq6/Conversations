package eu.siacs.conversations.feature.xmpp.command

import android.content.Intent
import android.net.Uri
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class DelegateUriPermissionsToService @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(uri: Uri) {
        val intent = Intent(
            activity,
            XmppConnectionService::class.java
        )
        intent.action = Intent.ACTION_SEND
        intent.data = uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startService(intent)
        } catch (e: Exception) {
            Timber.e("unable to delegate uri permission $e")
        }

    }
}