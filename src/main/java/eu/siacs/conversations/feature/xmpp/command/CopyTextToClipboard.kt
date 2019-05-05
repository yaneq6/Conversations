package eu.siacs.conversations.feature.xmpp.command

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class CopyTextToClipboard @Inject constructor(
    private val activity: XmppActivity,
    private val resources: Resources
) {
    operator fun invoke(text: String, labelResId: Int): Boolean {
        val mClipBoardManager =
            activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val label = resources.getString(labelResId)
        if (mClipBoardManager != null) {
            val mClipData = ClipData.newPlainText(label, text)
            mClipBoardManager.primaryClip = mClipData
            return true
        }
        return false
    }
}