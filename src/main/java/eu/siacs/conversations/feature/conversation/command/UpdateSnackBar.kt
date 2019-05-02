package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UpdateSnackBar @Inject constructor(
    private val fragment: ConversationFragment
) : (Conversation) -> Unit {
    override fun invoke(conversation: Conversation) = fragment.run {
        val account = conversation.account
        val connection = account.xmppConnection
        val mode = conversation.mode
        val contact = if (mode == Conversation.MODE_SINGLE) conversation.contact else null
        if (conversation.status == Conversation.STATUS_ARCHIVED) {
            return
        }
        if (account.status == Account.State.DISABLED) {
            showSnackbar(
                R.string.this_account_is_disabled,
                R.string.enable, this.mEnableAccountListener)
        } else if (conversation.isBlocked) {
            showSnackbar(R.string.contact_blocked, R.string.unblock, this.mUnblockClickListener)
        } else if (contact != null && !contact.showInRoster() && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
            showSnackbar(
                R.string.contact_added_you,
                R.string.add_back,
                this.mAddBackClickListener,
                this.mLongPressBlockListener
            )
        } else if (contact != null && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
            showSnackbar(
                R.string.contact_asks_for_presence_subscription,
                R.string.allow,
                this.mAllowPresenceSubscription,
                this.mLongPressBlockListener
            )
        } else if (mode == Conversation.MODE_MULTI
            && !conversation.mucOptions.online()
            && account.status == Account.State.ONLINE
        ) {
            when (conversation.mucOptions.error) {
                MucOptions.Error.NICK_IN_USE -> showSnackbar(
                    R.string.nick_in_use,
                    R.string.edit, clickToMuc)
                MucOptions.Error.NO_RESPONSE -> showSnackbar(R.string.joining_conference, 0, null)
                MucOptions.Error.SERVER_NOT_FOUND -> if (conversation.receivedMessagesCount() > 0) {
                    showSnackbar(
                        R.string.remote_server_not_found,
                        R.string.try_again, joinMuc)
                } else {
                    showSnackbar(
                        R.string.remote_server_not_found,
                        R.string.leave, leaveMuc)
                }
                MucOptions.Error.REMOTE_SERVER_TIMEOUT -> if (conversation.receivedMessagesCount() > 0) {
                    showSnackbar(
                        R.string.remote_server_timeout,
                        R.string.try_again, joinMuc)
                } else {
                    showSnackbar(
                        R.string.remote_server_timeout,
                        R.string.leave, leaveMuc)
                }
                MucOptions.Error.PASSWORD_REQUIRED -> showSnackbar(
                    R.string.conference_requires_password,
                    R.string.enter_password,
                    enterPassword
                )
                MucOptions.Error.BANNED -> showSnackbar(
                    R.string.conference_banned,
                    R.string.leave, leaveMuc)
                MucOptions.Error.MEMBERS_ONLY -> showSnackbar(
                    R.string.conference_members_only,
                    R.string.leave,
                    leaveMuc
                )
                MucOptions.Error.RESOURCE_CONSTRAINT -> showSnackbar(
                    R.string.conference_resource_constraint,
                    R.string.try_again,
                    joinMuc
                )
                MucOptions.Error.KICKED -> showSnackbar(
                    R.string.conference_kicked,
                    R.string.join, joinMuc)
                MucOptions.Error.UNKNOWN -> showSnackbar(
                    R.string.conference_unknown_error,
                    R.string.try_again,
                    joinMuc
                )
                MucOptions.Error.INVALID_NICK -> {
                    showSnackbar(
                        R.string.invalid_muc_nick,
                        R.string.edit, clickToMuc)
                    showSnackbar(
                        R.string.conference_shutdown,
                        R.string.try_again, joinMuc)
                }
                MucOptions.Error.SHUTDOWN -> showSnackbar(
                    R.string.conference_shutdown,
                    R.string.try_again, joinMuc)
                MucOptions.Error.DESTROYED -> showSnackbar(
                    R.string.conference_destroyed,
                    R.string.leave, leaveMuc)
                MucOptions.Error.NON_ANONYMOUS -> showSnackbar(
                    R.string.group_chat_will_make_your_jabber_id_public,
                    R.string.join,
                    acceptJoin
                )
                else -> hideSnackbar()
            }
        } else if (account.hasPendingPgpIntent(conversation)) {
            showSnackbar(
                R.string.openpgp_messages_found,
                R.string.decrypt, clickToDecryptListener)
        } else if (connection != null
            && connection.features.blocking()
            && conversation.countMessages() != 0
            && !conversation.isBlocked
            && conversation.isWithStranger
        ) {
            showSnackbar(
                R.string.received_message_from_stranger,
                R.string.block, mBlockClickListener)
        } else {
            hideSnackbar()
        }
    }
}