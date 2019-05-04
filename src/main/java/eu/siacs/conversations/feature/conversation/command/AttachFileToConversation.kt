package eu.siacs.conversations.feature.conversation.command

import android.app.PendingIntent
import android.net.Uri
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.UiInformableCallback
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AttachFileToConversation @Inject constructor(
    private val activity: XmppActivity,
    private val hidePrepareFileToast: HidePrepareFileToast
) : (Conversation?, Uri, String) -> Unit {

    override fun invoke(conversation: Conversation?, uri: Uri, type: String) {
        if (conversation == null) {
            return
        }
        val prepareFileToast = Toast.makeText(
            activity,
            activity.getText(R.string.preparing_file),
            Toast.LENGTH_LONG
        )
        prepareFileToast.show()
        activity.delegateUriPermissionsToService(uri)
        activity.xmppConnectionService.attachFileToConversation(
            conversation,
            uri,
            type,
            object : UiInformableCallback<Message> {
                override fun inform(text: String) {
                    hidePrepareFileToast(prepareFileToast)
                    activity.runOnUiThread { activity.replaceToast(text) }
                }

                override fun success(message: Message) {
                    activity.runOnUiThread { activity.hideToast() }
                    hidePrepareFileToast(prepareFileToast)
                }

                override fun error(errorCode: Int, message: Message) {
                    hidePrepareFileToast(prepareFileToast)
                    activity.runOnUiThread { activity.replaceToast(activity.getString(errorCode)) }

                }

                override fun userInputRequried(pi: PendingIntent, message: Message) {
                    hidePrepareFileToast(prepareFileToast)
                }
            })
    }
}