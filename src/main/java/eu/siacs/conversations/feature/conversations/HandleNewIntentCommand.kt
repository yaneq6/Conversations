package eu.siacs.conversations.feature.conversations

import android.content.Intent
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.util.PendingItem

import javax.inject.Inject

@ActivityScope
class HandleNewIntentCommand @Inject constructor(
    private val activity: ConversationsActivity,
    private val pendingViewIntent: PendingItem<Intent>,
    private val processViewIntent: ProcessViewIntentCommand
) : (Intent) -> Unit {

    override fun invoke(intent: Intent) {
        if (ConversationsActivity.isViewOrShareIntent(intent)) {
            if (activity.xmppConnectionService != null) {
                pendingViewIntent.clear()
                processViewIntent(intent)
            } else {
                pendingViewIntent.push(intent)
            }
        }
        activity.intent = ConversationsActivity.createLauncherIntent(activity)
    }
}