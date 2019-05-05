package eu.siacs.conversations.feature.conversation.command

import android.app.PendingIntent
import android.content.DialogInterface
import android.view.Gravity
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.conversation.REQUEST_ENCRYPT_MESSAGE
import eu.siacs.conversations.feature.xmpp.command.AnnouncePgp
import eu.siacs.conversations.feature.xmpp.command.ShowInstallPgpDialog
import eu.siacs.conversations.feature.xmpp.query.HasPgp
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.UiCallback
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class SendPgpMessage @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val showNoPGPKeyDialog: ShowNoPGPKeyDialog,
    private val startPendingIntent: StartPendingIntent,
    private val encryptTextMessage: EncryptTextMessage,
    private val messageSent: MessageSent,
    private val hasPgp: HasPgp,
    private val showInstallPgpDialog: ShowInstallPgpDialog,
    private val announcePgp: AnnouncePgp
) : (Message) -> Unit {

    override fun invoke(message: Message) {
        val conversation = fragment.conversation!!
        val xmppService = activity.xmppConnectionService
        val contact = message.conversation.contact
        if (!hasPgp()) {
            showInstallPgpDialog()
            return
        }
        if (conversation.account.pgpSignature == null) {
            announcePgp(conversation.account, conversation, null, activity.onOpenPGPKeyPublished)
            return
        }
        if (!fragment.sendingPgpMessage.compareAndSet(false, true)) {
            Timber.d("sending pgp message already in progress")
        }
        if (conversation.mode == Conversation.MODE_SINGLE) {
            if (contact.pgpKeyId != 0L) {
                xmppService.pgpEngine!!.hasKey(contact,
                    object : UiCallback<Contact> {

                        override fun userInputRequried(pi: PendingIntent, contact: Contact) {
                            startPendingIntent(pi, REQUEST_ENCRYPT_MESSAGE)
                        }

                        override fun success(contact: Contact) {
                            encryptTextMessage(message)
                        }

                        override fun error(error: Int, contact: Contact) {
                            activity.runOnUiThread {
                                Toast.makeText(
                                    activity,
                                    R.string.unable_to_connect_to_keychain,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            fragment.sendingPgpMessage.set(false)
                        }
                    })

            } else {
                showNoPGPKeyDialog(false, DialogInterface.OnClickListener { _, _ ->
                    conversation.nextEncryption = Message.ENCRYPTION_NONE
                    xmppService.updateConversation(conversation)
                    message.encryption = Message.ENCRYPTION_NONE
                    xmppService.sendMessage(message)
                    messageSent()
                })
            }
        } else {
            if (conversation.mucOptions.pgpKeysInUse()) {
                if (!conversation.mucOptions.everybodyHasKeys()) {
                    val warning = Toast.makeText(
                        activity,
                        R.string.missing_public_keys,
                        Toast.LENGTH_LONG
                    )
                    warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
                    warning.show()
                }
                encryptTextMessage(message)
            } else {
                showNoPGPKeyDialog(true, DialogInterface.OnClickListener { _, _ ->
                    conversation.nextEncryption = Message.ENCRYPTION_NONE
                    message.encryption = Message.ENCRYPTION_NONE
                    xmppService.updateConversation(conversation)
                    xmppService.sendMessage(message)
                    messageSent()
                })
            }
        }
    }
}