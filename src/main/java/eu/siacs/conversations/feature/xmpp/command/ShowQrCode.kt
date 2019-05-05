package eu.siacs.conversations.feature.xmpp.command

import android.graphics.Color
import android.graphics.Point
import android.support.v7.app.AlertDialog
import android.widget.ImageView
import eu.siacs.conversations.services.BarcodeProvider
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowQrCode @Inject constructor(
   private val activity: XmppActivity
) {

    @JvmOverloads
    operator fun invoke(uri: String? = activity.shareableUri) {
        if (uri == null || uri.isEmpty()) {
            return
        }
        val size = Point()
        activity.windowManager.defaultDisplay.getSize(size)
        val width = if (size.x < size.y) size.x else size.y
        val bitmap =
            BarcodeProvider.create2dBarcodeBitmap(uri, width)
        val view = ImageView(activity)
        view.setBackgroundColor(Color.WHITE)
        view.setImageBitmap(bitmap)
        val builder = AlertDialog.Builder(activity)
        builder.setView(view)
        builder.create().show()
    }
}