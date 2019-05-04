package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.ViewUtil
import eu.siacs.conversations.utils.GeoHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OpenWith @Inject constructor(
    private val activity: XmppActivity
) : (Message) -> Unit {
    override fun invoke(message: Message) {
        if (message.isGeoUri) {
            GeoHelper.view(activity, message)
        } else {
            val file = activity.xmppConnectionService.fileBackend.getFile(message)
            ViewUtil.view(activity, file)
        }
    }
}