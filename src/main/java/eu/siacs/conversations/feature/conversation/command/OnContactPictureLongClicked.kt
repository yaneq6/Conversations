package eu.siacs.conversations.feature.conversation.command

import android.view.View
import android.widget.PopupMenu
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.xmpp.command.ShowQrCode
import eu.siacs.conversations.feature.xmpp.command.SwitchToAccount
import eu.siacs.conversations.services.QuickConversationsService
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper
import eu.siacs.conversations.utils.AccountUtils
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnContactPictureLongClicked @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val switchToAccount: SwitchToAccount,
    private val showQrCode: ShowQrCode
) {
    operator fun invoke(v: View, message: Message) {
        val fingerprint = if (message.encryption == Message.ENCRYPTION_PGP
            || message.encryption == Message.ENCRYPTION_DECRYPTED
        ) {
            "pgp"
        } else {
            message.fingerprint
        }
        val popupMenu = PopupMenu(activity, v)
        val contact = message.contact
        val conversation = fragment.conversation!!
        if (message.status <= Message.STATUS_RECEIVED && (contact == null || !contact.isSelf)) {
            if (message.conversation.mode == Conversation.MODE_MULTI) {
                val cp = message.counterpart
                if (cp == null || cp.isBareJid) {
                    return
                }
                val tcp = message.trueCounterpart
                val userByRealJid =
                    if (tcp != null) conversation.mucOptions.findOrCreateUserByRealJid(
                        tcp,
                        cp
                    ) else null
                val user = userByRealJid ?: conversation.mucOptions.findUserByFullJid(cp)
                popupMenu.inflate(R.menu.muc_details_context)
                val menu = popupMenu.menu
                MucDetailsContextMenuHelper.configureMucDetailsContextMenu(
                    activity,
                    menu,
                    conversation,
                    user
                )
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    MucDetailsContextMenuHelper.onContextItemSelected(
                        menuItem,
                        user!!,
                        activity,
                        fingerprint
                    )
                }
            } else {
                popupMenu.inflate(R.menu.one_on_one_context)
                popupMenu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_contact_details -> activity.switchToContactDetails(
                            message.contact,
                            fingerprint
                        )
                        R.id.action_show_qr_code -> showQrCode("xmpp:" + message.contact!!.jid.asBareJid().toEscapedString())
                    }
                    true
                }
            }
        } else {
            popupMenu.inflate(R.menu.account_context)
            val menu = popupMenu.menu
            menu.findItem(R.id.action_manage_accounts).isVisible =
                QuickConversationsService.isConversations()
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_show_qr_code -> showQrCode(conversation.account.shareableUri)
                    R.id.action_account_details -> switchToAccount(
                        message.conversation.account,
                        fingerprint
                    )
                    R.id.action_manage_accounts -> AccountUtils.launchManageAccounts(
                        activity
                    )
                }
                true
            }
        }
        popupMenu.show()
    }
}