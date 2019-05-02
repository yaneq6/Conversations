package eu.siacs.conversations.feature.conversation.command

import android.app.PendingIntent
import android.content.DialogInterface
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.UiCallback
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SendPgpMessage @Inject constructor(
    private val fragment: ConversationFragment,
    private val showNoPGPKeyDialog: ShowNoPGPKeyDialog
) : (Message) -> Unit {
    override fun invoke(message: Message) = fragment.run {
        val xmppService = activity!!.xmppConnectionService
        val contact = message.conversation.contact
        if (!activity!!.hasPgp()) {
            activity!!.showInstallPgpDialog()
            return
        }
        if (conversation!!.account.pgpSignature == null) {
            activity!!.announcePgp(conversation!!.account, conversation, null, activity!!.onOpenPGPKeyPublished)
            return
        }
        if (!mSendingPgpMessage.compareAndSet(false, true)) {
            Log.d(Config.LOGTAG, "sending pgp message already in progress")
        }
        if (conversation!!.mode == Conversation.MODE_SINGLE) {
            if (contact.pgpKeyId != 0L) {
                xmppService.pgpEngine!!.hasKey(contact,
                    object : UiCallback<Contact> {

                        override fun userInputRequried(pi: PendingIntent, contact: Contact) {
                            startPendingIntent(pi,
                                ConversationFragment.REQUEST_ENCRYPT_MESSAGE
                            )
                        }

                        override fun success(contact: Contact) {
                            encryptTextMessage(message)
                        }

                        override fun error(error: Int, contact: Contact) {
                            activity!!.runOnUiThread {
                                Toast.makeText(
                                    activity,
                                    R.string.unable_to_connect_to_keychain,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            mSendingPgpMessage.set(false)
                        }
                    })

            } else {
                showNoPGPKeyDialog(false, DialogInterface.OnClickListener { _, _ ->
                    conversation!!.nextEncryption = Message.ENCRYPTION_NONE
                    xmppService.updateConversation(conversation)
                    message.encryption = Message.ENCRYPTION_NONE
                    xmppService.sendMessage(message)
                    messageSent()
                })
            }
        } else {
            if (conversation!!.mucOptions.pgpKeysInUse()) {
                if (!conversation!!.mucOptions.everybodyHasKeys()) {
                    val warning = Toast.makeText(
                        getActivity(),
                        R.string.missing_public_keys,
                        Toast.LENGTH_LONG
                    )
                    warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
                    warning.show()
                }
                encryptTextMessage(message)
            } else {
                showNoPGPKeyDialog(true, DialogInterface.OnClickListener { _, _ ->
                    conversation!!.nextEncryption = Message.ENCRYPTION_NONE
                    message.encryption = Message.ENCRYPTION_NONE
                    xmppService.updateConversation(conversation)
                    xmppService.sendMessage(message)
                    messageSent()
                })
            }
        }
    }
}