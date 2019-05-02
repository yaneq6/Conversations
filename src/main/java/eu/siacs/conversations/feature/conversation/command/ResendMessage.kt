package eu.siacs.conversations.feature.conversation.command

import android.os.Handler
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.utils.Compatibility
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ResendMessage @Inject constructor(
    private val fragment: ConversationFragment
) : (Message) -> Unit {
    override fun invoke(message: Message) = fragment.run {
        if (message.isFileOrImage) {
            if (message.conversation !is Conversation) {
                return
            }
            val conversation = message.conversation as Conversation
            val file = activity!!.xmppConnectionService.fileBackend.getFile(message)
            if (file.exists() && file.canRead() || message.hasFileOnRemoteHost()) {
                val xmppConnection = conversation.account.xmppConnection
                if (!message.hasFileOnRemoteHost()
                    && xmppConnection != null
                    && conversation.mode == Conversational.MODE_SINGLE
                    && !xmppConnection.features.httpUpload(message.fileParams.size)
                ) {
                    activity!!.selectPresence(conversation) {
                        message.counterpart = conversation.nextCounterpart
                        activity!!.xmppConnectionService.resendFailedMessages(message)
                        Handler().post {
                            val size = messageList.size
                            this.binding!!.messagesView.setSelection(size - 1)
                        }
                    }
                    return
                }
            } else if (!Compatibility.hasStoragePermission(getActivity())) {
                Toast.makeText(
                    activity,
                    R.string.no_storage_permission,
                    Toast.LENGTH_SHORT
                ).show()
                return
            } else {
                Toast.makeText(
                    activity,
                    R.string.file_deleted,
                    Toast.LENGTH_SHORT
                ).show()
                message.isDeleted = true
                activity!!.xmppConnectionService.updateMessage(message, false)
                activity!!.onConversationsListItemUpdated()
                refresh()
                return
            }
        }
        activity!!.xmppConnectionService.resendFailedMessages(message)
        Handler().post {
            val size = messageList.size
            this.binding!!.messagesView.setSelection(size - 1)
        }
        Unit
    }
}