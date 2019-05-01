package eu.siacs.conversations.feature.conversations

import android.content.Intent
import android.os.Bundle
import eu.siacs.conversations.ui.ConversationsActivity
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class ProcessViewIntentCommand @Inject constructor(
    private val activity: ConversationsActivity,
    private val openConversation: OpenConversationCommand
) : (Intent) -> Boolean {

    override fun invoke(intent: Intent): Boolean {
        val uuid = intent.getStringExtra(ConversationsActivity.EXTRA_CONVERSATION)
        val conversation = if (uuid != null) activity.xmppConnectionService?.findConversationByUuid(uuid) else null
        if (conversation == null) {
            Timber.d("unable to view conversation with uuid:$uuid")
            return false
        }
        openConversation(
            conversation = conversation,
            extras = intent.extras ?: Bundle()
        )
        return true
    }
}