package eu.siacs.conversations.feature.conversation.command

import android.support.v7.app.AlertDialog
import android.widget.CheckBox
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ClearHistoryDialog @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : (Conversation) -> Unit {

    override fun invoke(conversation: Conversation) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(activity.getString(R.string.clear_conversation_history))
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_clear_history, null)
        val endConversationCheckBox = dialogView.findViewById<CheckBox>(R.id.end_conversation_checkbox)
        builder.setView(dialogView)
        builder.setNegativeButton(activity.getString(R.string.cancel), null)
        builder.setPositiveButton(activity.getString(R.string.confirm)) { dialog, which ->
            activity.xmppConnectionService.clearConversationHistory(conversation)
            if (endConversationCheckBox.isChecked) {
                activity.xmppConnectionService.archiveConversation(conversation)
                activity.onConversationArchived(conversation)
            } else {
                activity.onConversationsListItemUpdated()
                fragment.refresh()
            }
        }
        builder.create().show()
    }
}