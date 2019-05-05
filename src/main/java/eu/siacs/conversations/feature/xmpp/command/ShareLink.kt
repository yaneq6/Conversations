package eu.siacs.conversations.feature.xmpp.command

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShareLink @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(http: Boolean) {
        val uri = activity.getShareableUri(http)
        if (uri == null || uri.isEmpty()) {
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, activity.getShareableUri(http))
        try {
            activity.startActivity(
                Intent.createChooser(
                    intent,
                    activity.getText(R.string.share_uri_with)
                )
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                activity,
                R.string.no_application_to_share_uri,
                Toast.LENGTH_SHORT
            )
                .show()
        }

    }
}