package eu.siacs.conversations.feature.conversation.command

import android.content.res.Resources
import android.preference.PreferenceManager
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnEnterPressed @Inject constructor(
    private val activity: ConversationsActivity,
    private val resources: Resources,
    private val sendMessage: SendMessage
) {
    operator fun invoke(): Boolean {
        val p = PreferenceManager.getDefaultSharedPreferences(activity)
        val enterIsSend = p.getBoolean("enter_is_send", resources.getBoolean(R.bool.enter_is_send))
        return if (enterIsSend) {
            sendMessage()
            true
        } else {
            false
        }
    }
}