package eu.siacs.conversations.feature.xmpp.query

import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject


@ActivityScope
class ShareableUri @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(): String? = activity.getShareableUri(false)
}