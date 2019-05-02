package eu.siacs.conversations.feature.conversation.command

import android.app.PendingIntent
import android.net.Uri
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AttachLocationToConversation @Inject constructor(
    private val activity: XmppActivity
) : (Conversation?, Uri) -> Unit {
    override fun invoke(conversation: Conversation?, uri: Uri) {
        if (conversation == null) {
            return
        }
        activity.xmppConnectionService.attachLocationToConversation(
            conversation,
            uri,
            object : UiCallback<Message> {

                override fun success(message: Message) {

                }

                override fun error(errorCode: Int, `object`: Message) {
                    //TODO show possible pgp error
                }

                override fun userInputRequried(pi: PendingIntent, `object`: Message) {

                }
            })
    }
}