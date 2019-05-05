package eu.siacs.conversations.feature.xmpp.query

import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HasPgp @Inject constructor(
    private val activity: XmppActivity
) : () -> Boolean {
    override fun invoke(): Boolean = activity.run {
        xmppConnectionService.pgpEngine != null
    }
}