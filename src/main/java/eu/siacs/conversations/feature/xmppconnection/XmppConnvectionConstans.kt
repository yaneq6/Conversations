package eu.siacs.conversations.feature.xmppconnection

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.http.CustomURLStreamHandlerFactory
import eu.siacs.conversations.xmpp.pep.Avatar
import java.net.URL

object XmppConnvectionConstans {

    @JvmField
    val ACTION_REPLY_TO_CONVERSATION = "reply_to_conversations"
    @JvmField
    val ACTION_MARK_AS_READ = "mark_as_read"
    @JvmField
    val ACTION_SNOOZE = "snooze"
    @JvmField
    val ACTION_CLEAR_NOTIFICATION = "clear_notification"
    @JvmField
    val ACTION_DISMISS_ERROR_NOTIFICATIONS = "dismiss_error"
    @JvmField
    val ACTION_TRY_AGAIN = "try_again"
    @JvmField
    val ACTION_IDLE_PING = "idle_ping"
    @JvmField
    val ACTION_FCM_TOKEN_REFRESH = "fcm_token_refresh"
    @JvmField
    val ACTION_FCM_MESSAGE_RECEIVED = "fcm_message_received"
    val ACTION_POST_CONNECTIVITY_CHANGE =
        "eu.siacs.conversations.POST_CONNECTIVITY_CHANGE"

    val SETTING_LAST_ACTIVITY_TS = "last_activity_timestamp"

    init {
        URL.setURLStreamHandlerFactory(CustomURLStreamHandlerFactory())
    }

    fun generateFetchKey(account: Account, avatar: Avatar): String {
        return account.jid.asBareJid().toString() + "_" + avatar.owner + "_" + avatar.sha1sum
    }
}