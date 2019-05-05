package eu.siacs.conversations.feature.conversation.command

import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.xmpp.command.SwitchToAccount
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject


@ActivityScope
class OnContactPictureClicked @Inject constructor(
    private val activity: ConversationsActivity,
    private val highlightInConference: HighlightInConference,
    private val switchToAccount: SwitchToAccount
) {
    operator fun invoke(message: Message) {
        val fingerprint: String
        if (message.encryption == Message.ENCRYPTION_PGP || message.encryption == Message.ENCRYPTION_DECRYPTED) {
            fingerprint = "pgp"
        } else {
            fingerprint = message.fingerprint
        }
        val received = message.status <= Message.STATUS_RECEIVED
        if (received) {
            if (message.conversation is Conversation && message.conversation.mode == Conversation.MODE_MULTI) {
                val tcp = message.trueCounterpart
                val user = message.counterpart
                if (user != null && !user.isBareJid) {
                    val mucOptions = (message.conversation as Conversation).mucOptions
                    if (mucOptions.participating() || (message.conversation as Conversation).nextCounterpart != null) {
                        if (!mucOptions.isUserInRoom(user) && mucOptions.findUserByRealJid(tcp?.asBareJid()) == null) {
                            Toast.makeText(
                                activity,
                                activity.getString(
                                    R.string.user_has_left_conference,
                                    user.resource
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        highlightInConference(user.resource)
                    } else {
                        Toast.makeText(
                            activity,
                            R.string.you_are_not_participating,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return
            } else {
                if (!message.contact!!.isSelf) {
                    activity.switchToContactDetails(message.contact, fingerprint)
                    return
                }
            }
        }
        switchToAccount(message.conversation.account, fingerprint)
    }
}