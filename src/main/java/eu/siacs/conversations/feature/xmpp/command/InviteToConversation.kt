package eu.siacs.conversations.feature.xmpp.command

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ChooseContactActivity
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class InviteToConversation @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(conversation: Conversation) {
        activity.startActivityForResult(
            ChooseContactActivity.create(activity, conversation),
            XmppActivity.REQUEST_INVITE_TO_CONVERSATION
        )
    }
}