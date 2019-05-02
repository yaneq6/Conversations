package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.ViewUtil
import eu.siacs.conversations.utils.GeoHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OpenWith @Inject constructor(
    private val fragment: ConversationFragment
) : (Message) -> Unit {
    override fun invoke(message: Message) = fragment.run {
        if (message.isGeoUri) {
            GeoHelper.view(getActivity(), message)
        } else {
            val file = activity!!.xmppConnectionService.fileBackend.getFile(message)
            ViewUtil.view(activity, file)
        }
    }
}