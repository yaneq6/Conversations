package eu.siacs.conversations.feature.xmpp.command

import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HideToast @Inject constructor(private val activity: XmppActivity) {
    operator fun invoke(): Unit = activity.run {
        if (mToast != null) {
            mToast!!.cancel()
        }
    }
}