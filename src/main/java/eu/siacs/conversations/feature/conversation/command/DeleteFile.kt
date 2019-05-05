package eu.siacs.conversations.feature.conversation.command

import android.support.v7.app.AlertDialog
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class DeleteFile @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : (Message?) -> Unit {
    
    override fun invoke(message: Message?) = AlertDialog.Builder(activity).run {
        setNegativeButton(R.string.cancel, null)
        setTitle(R.string.delete_file_dialog)
        setMessage(R.string.delete_file_dialog_msg)
        setPositiveButton(R.string.confirm) { dialog, which ->
            if (activity.xmppConnectionService.fileBackend.deleteFile(message)) {
                message?.isDeleted = true
                activity.xmppConnectionService.updateMessage(message!!, false)
                activity.onConversationsListItemUpdated()
                fragment.refresh()
            }
        }.create().show()
    }
}