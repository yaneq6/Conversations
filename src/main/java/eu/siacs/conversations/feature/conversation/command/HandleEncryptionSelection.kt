package eu.siacs.conversations.feature.conversation.command

import android.annotation.SuppressLint
import android.view.MenuItem
import eu.siacs.conversations.R
import eu.siacs.conversations.crypto.axolotl.AxolotlService
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.xmpp.callback.OnOpenPGPKeyPublished
import eu.siacs.conversations.feature.xmpp.command.AnnouncePgp
import eu.siacs.conversations.feature.xmpp.command.RefreshUi
import eu.siacs.conversations.feature.xmpp.command.ShowInstallPgpDialog
import eu.siacs.conversations.feature.xmpp.query.HasPgp
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
    private val updateChatMsgHint: UpdateChatMsgHint,
    private val hasPgp: HasPgp,
    private val showInstallPgpDialog: ShowInstallPgpDialog,
    private val announcePgp: AnnouncePgp,
    private val onOpenPGPKeyPublished: OnOpenPGPKeyPublished,
    private val refreshUi: RefreshUi
) : (MenuItem) -> Unit {

    override fun invoke(item: MenuItem) {
        val conversation = fragment.conversation ?: return
        val updated: Boolean
        when (item.itemId) {
            R.id.encryption_choice_none -> {
                updated = conversation.setNextEncryption(Message.ENCRYPTION_NONE)
                item.isChecked = true
            }
            R.id.encryption_choice_pgp -> if (hasPgp()) {
                if (conversation.account.pgpSignature != null) {
                    updated = conversation.setNextEncryption(Message.ENCRYPTION_PGP)
                    item.isChecked = true
                } else {
                    updated = false
                    announcePgp(
                        conversation.account,
                        conversation,
                        null,
                        onOpenPGPKeyPublished
                    )
                }
            } else {
                showInstallPgpDialog()
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
        refreshUi()
    }
}