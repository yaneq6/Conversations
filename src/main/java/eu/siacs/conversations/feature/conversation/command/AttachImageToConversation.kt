package eu.siacs.conversations.feature.conversation.command

import android.app.PendingIntent
import android.net.Uri
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AttachImageToConversation @Inject constructor(
    private val activity: XmppActivity,
    private val hidePrepareFileToast: HidePrepareFileToast
) : (Conversation?, Uri) -> Unit {

    override fun invoke(conversation: Conversation?, uri: Uri) {
        if (conversation == null) {
            return
        }
        val prepareFileToast = Toast.makeText(
            activity,
            activity.getText(R.string.preparing_image),
            Toast.LENGTH_LONG
        )
        prepareFileToast.show()
        activity.delegateUriPermissionsToService(uri)
        activity.xmppConnectionService.attachImageToConversation(
            conversation,
            uri,
            object : UiCallback<Message> {

                override fun userInputRequried(pi: PendingIntent, `object`: Message) {
                    hidePrepareFileToast(prepareFileToast)
                }

                override fun success(message: Message) {
                    hidePrepareFileToast(prepareFileToast)
                }

                override fun error(error: Int, message: Message) {
                    hidePrepareFileToast(prepareFileToast)
                    activity.runOnUiThread { activity.replaceToast(activity.getString(error)) }
                }
            })
    }

}