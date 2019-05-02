package eu.siacs.conversations.feature.conversation.command

import android.annotation.SuppressLint
import android.view.MenuItem
import eu.siacs.conversations.R
import eu.siacs.conversations.crypto.axolotl.AxolotlService
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
@SuppressLint("BinaryOperationInTimber")
class HandleEncryptionSelection @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity,
    private val updateChatMsgHint: UpdateChatMsgHint
) : (MenuItem) -> Unit {
    override fun invoke(item: MenuItem) {
        val conversation = fragment.conversation ?: return
        val updated: Boolean
        when (item.itemId) {
            R.id.encryption_choice_none -> {
                updated = conversation.setNextEncryption(Message.ENCRYPTION_NONE)
                item.isChecked = true
            }
            R.id.encryption_choice_pgp -> if (activity.hasPgp()) {
                if (conversation.account.pgpSignature != null) {
                    updated = conversation.setNextEncryption(Message.ENCRYPTION_PGP)
                    item.isChecked = true
                } else {
                    updated = false
                    activity.announcePgp(
                        conversation.account,
                        conversation,
                        null,
                        activity.onOpenPGPKeyPublished
                    )
                }
            } else {
                activity.showInstallPgpDialog()
                updated = false
            }
            R.id.encryption_choice_axolotl -> {
                Timber.d(AxolotlService.getLogprefix(conversation.account) + "Enabled axolotl for Contact " + conversation.contact.jid)
                updated = conversation.setNextEncryption(Message.ENCRYPTION_AXOLOTL)
                item.isChecked = true
            }
            else -> updated = conversation.setNextEncryption(Message.ENCRYPTION_NONE)
        }
        if (updated) {
            activity.xmppConnectionService.updateConversation(conversation)
        }
        updateChatMsgHint()
        activity.invalidateOptionsMenu()
        activity.refreshUi()
    }
}