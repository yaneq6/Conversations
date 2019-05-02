package eu.siacs.conversations.feature.conversation.command

import android.content.res.Resources
import android.support.v7.app.AlertDialog
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.utils.TimeframeUtils
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class MuteConversationDialog @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val resources: Resources
) : (Conversation) -> Unit {
    override fun invoke(conversation: Conversation) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(R.string.disable_notifications)
        val durations = resources.getIntArray(R.array.mute_options_durations)
        val labels = arrayOfNulls<CharSequence>(durations.size)
        for (i in durations.indices) {
            if (durations[i] == -1) {
                labels[i] = activity.getString(R.string.until_further_notice)
            } else {
                labels[i] = TimeframeUtils.resolve(activity, 1000L * durations[i])
            }
        }
        builder.setItems(labels) { dialog, which ->
            val till: Long
            if (durations[which] == -1) {
                till = java.lang.Long.MAX_VALUE
            } else {
                till = System.currentTimeMillis() + durations[which] * 1000
            }
            conversation.setMutedTill(till)
            activity.xmppConnectionService.updateConversation(conversation)
            activity.onConversationsListItemUpdated()
            fragment.refresh()
            activity.invalidateOptionsMenu()
        }
        builder.create().show()
    }
}