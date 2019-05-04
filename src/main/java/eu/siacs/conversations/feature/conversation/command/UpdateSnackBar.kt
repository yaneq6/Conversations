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
    private val fragment: ConversationFragment,
    private val showSnackbar: ShowSnackbar,
    private val hideSnackbar: HideSnackbar
) : (Conversation) -> Unit {

    private val enableAccountListener get() = fragment.enableAccountListener
    private val unblockClickListener get() = fragment.unblockClickListener
    private val addBackClickListener get() = fragment.addBackClickListener
    private val longPressBlockListener get() = fragment.longPressBlockListener
    private val allowPresenceSubscription get() = fragment.allowPresenceSubscription


    override fun invoke(conversation: Conversation) {
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
                R.string.enable, enableAccountListener
            )
        } else if (conversation.isBlocked) {
            showSnackbar(R.string.contact_blocked, R.string.unblock, unblockClickListener)
        } else if (contact != null && !contact.showInRoster() && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
            showSnackbar(
                R.string.contact_added_you,
                R.string.add_back,
                addBackClickListener,
                longPressBlockListener
            )
        } else if (contact != null && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
            showSnackbar(
                R.string.contact_asks_for_presence_subscription,
                R.string.allow,
                allowPresenceSubscription,
                longPressBlockListener
            )
        } else if (mode == Conversation.MODE_MULTI
            && !conversation.mucOptions.online()
            && account.status == Account.State.ONLINE
        ) {
            when (conversation.mucOptions.error) {
                MucOptions.Error.NICK_IN_USE -> showSnackbar(
                    R.string.nick_in_use,
                    R.string.edit,
                    fragment.clickToMuc
                )
                MucOptions.Error.NO_RESPONSE -> showSnackbar(
                    R.string.joining_conference,
                    0,
                    null
                )
                MucOptions.Error.SERVER_NOT_FOUND ->
                    if (conversation.receivedMessagesCount() > 0) {
                        showSnackbar(
                            R.string.remote_server_not_found,
                            R.string.try_again,
                            fragment.joinMuc
                        )
                    } else {
                        showSnackbar(
                            R.string.remote_server_not_found,
                            R.string.leave,
                            fragment.leaveMuc
                        )
                    }
                MucOptions.Error.REMOTE_SERVER_TIMEOUT ->
                    if (conversation.receivedMessagesCount() > 0) {
                        showSnackbar(
                            R.string.remote_server_timeout,
                            R.string.try_again,
                            fragment.joinMuc
                        )
                    } else {
                        showSnackbar(
                            R.string.remote_server_timeout,
                            R.string.leave,
                            fragment.leaveMuc
                        )
                    }
                MucOptions.Error.PASSWORD_REQUIRED -> showSnackbar(
                    R.string.conference_requires_password,
                    R.string.enter_password,
                    fragment.enterPassword
                )
                MucOptions.Error.BANNED -> showSnackbar(
                    R.string.conference_banned,
                    R.string.leave,
                    fragment.leaveMuc
                )
                MucOptions.Error.MEMBERS_ONLY -> showSnackbar(
                    R.string.conference_members_only,
                    R.string.leave,
                    fragment.leaveMuc
                )
                MucOptions.Error.RESOURCE_CONSTRAINT -> showSnackbar(
                    R.string.conference_resource_constraint,
                    R.string.try_again,
                    fragment.joinMuc
                )
                MucOptions.Error.KICKED -> showSnackbar(
                    R.string.conference_kicked,
                    R.string.join,
                    fragment.joinMuc
                )
                MucOptions.Error.UNKNOWN -> showSnackbar(
                    R.string.conference_unknown_error,
                    R.string.try_again,
                    fragment.joinMuc
                )
                MucOptions.Error.INVALID_NICK -> {
                    showSnackbar(
                        R.string.invalid_muc_nick,
                        R.string.edit,
                        fragment.clickToMuc
                    )
                    showSnackbar(
                        R.string.conference_shutdown,
                        R.string.try_again,
                        fragment.joinMuc
                    )
                }
                MucOptions.Error.SHUTDOWN -> showSnackbar(
                    R.string.conference_shutdown,
                    R.string.try_again,
                    fragment.joinMuc
                )
                MucOptions.Error.DESTROYED -> showSnackbar(
                    R.string.conference_destroyed,
                    R.string.leave,
                    fragment.leaveMuc
                )
                MucOptions.Error.NON_ANONYMOUS -> showSnackbar(
                    R.string.group_chat_will_make_your_jabber_id_public,
                    R.string.join,
                    fragment.acceptJoin
                )
                else -> hideSnackbar()
            }
        } else if (account.hasPendingPgpIntent(conversation)) {
            showSnackbar(
                R.string.openpgp_messages_found,
                R.string.decrypt,
                fragment.clickToDecryptListener
            )
        } else if (connection != null
            && connection.features.blocking()
            && conversation.countMessages() != 0
            && !conversation.isBlocked
            && conversation.isWithStranger
        ) {
            showSnackbar(
                R.string.received_message_from_stranger,
                R.string.block,
                fragment.blockClickListener
            )
        } else {
            hideSnackbar()
        }
    }
}