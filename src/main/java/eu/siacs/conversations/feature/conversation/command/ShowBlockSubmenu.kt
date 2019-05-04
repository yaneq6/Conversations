package eu.siacs.conversations.feature.conversation.command

import android.view.View
import android.widget.PopupMenu
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Blockable
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.ui.BlockContactDialog
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import rocks.xmpp.addr.Jid
import javax.inject.Inject

@ActivityScope
class ShowBlockSubmenu @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity,
    private val updateSnackBar: UpdateSnackBar
) : (View) -> Boolean {
    override fun invoke(view: View): Boolean {
        val conversation = fragment.conversation!!
        val jid = conversation.jid
        val showReject = conversation.run {
            !isWithStranger && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
        }
        val popupMenu = PopupMenu(activity, view)
        popupMenu.inflate(R.menu.block)
        popupMenu.menu.findItem(R.id.block_contact).isVisible = jid.local != null
        popupMenu.menu.findItem(R.id.reject).isVisible = showReject
        popupMenu.setOnMenuItemClickListener { menuItem ->
            val blockable: Blockable = when (menuItem.itemId) {
                R.id.reject -> {
                    activity.xmppConnectionService.stopPresenceUpdatesTo(conversation.contact)
                    updateSnackBar(conversation)
                    popupMenu.setOnMenuItemClickListener { true }
                    conversation
                }
                R.id.block_domain -> conversation.account.roster.getContact(
                    Jid.ofDomain(
                        jid.domain
                    )
                )
                else -> conversation
            }
            BlockContactDialog.show(activity, blockable)
            true
        }
        popupMenu.show()
        return true
    }
}