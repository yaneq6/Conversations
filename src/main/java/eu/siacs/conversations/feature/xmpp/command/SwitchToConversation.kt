package eu.siacs.conversations.feature.xmpp.command

import android.content.Intent
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SwitchToConversation @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(conversation: Conversation) {
        invoke(conversation, null)
    }

    operator fun invoke(conversation: Conversation, text: String?) {
        invoke(conversation, text, false, null, false, false)
    }

    operator fun invoke(
        conversation: Conversation,
        text: String?,
        asQuote: Boolean,
        nick: String?,
        pm: Boolean,
        doNotAppend: Boolean
    ) {
        val intent = Intent(
            activity,
            ConversationsActivity::class.java
        )
        intent.action =
            ConversationsActivity.ACTION_VIEW_CONVERSATION
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.uuid)
        if (text != null) {
            intent.putExtra(Intent.EXTRA_TEXT, text)
            if (asQuote) {
                intent.putExtra(ConversationsActivity.EXTRA_AS_QUOTE, true)
            }
        }
        if (nick != null) {
            intent.putExtra(ConversationsActivity.EXTRA_NICK, nick)
            intent.putExtra(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, pm)
        }
        if (doNotAppend) {
            intent.putExtra(ConversationsActivity.EXTRA_DO_NOT_APPEND, true)
        }
        intent.flags = intent.flags or Intent.FLAG_ACTIVITY_CLEAR_TOP
        activity.startActivity(intent)
        activity.finish()
    }
}