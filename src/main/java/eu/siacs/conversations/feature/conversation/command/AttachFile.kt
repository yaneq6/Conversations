package eu.siacs.conversations.feature.conversation.command

import android.Manifest
import android.app.PendingIntent
import android.content.DialogInterface
import android.view.Gravity
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.conversation.*
import eu.siacs.conversations.feature.xmpp.command.ShowInstallPgpDialog
import eu.siacs.conversations.feature.xmpp.query.HasPgp
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.SendButtonAction
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AttachFile @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity,
    private val hasPermissions: HasPermissions,
    private val showNoPGPKeyDialog: ShowNoPGPKeyDialog,
    private val startPendingIntent: StartPendingIntent,
    private val selectPresenceToAttachFile: SelectPresenceToAttachFile,
    private val hasPgp: HasPgp,
    private val showInstallPgpDialog: ShowInstallPgpDialog
) : (Int) -> Unit {

    override fun invoke(attachmentChoice: Int) {
        if (attachmentChoice == ATTACHMENT_CHOICE_RECORD_VOICE) {
            if (!hasPermissions(
                    attachmentChoice,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO
                )
            ) return
        } else if (attachmentChoice == ATTACHMENT_CHOICE_TAKE_PHOTO || attachmentChoice == ATTACHMENT_CHOICE_RECORD_VIDEO) {
            if (!hasPermissions(
                    attachmentChoice,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA
                )
            ) return
        } else if (attachmentChoice != ATTACHMENT_CHOICE_LOCATION) {
            if (!hasPermissions(attachmentChoice, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                return
        }
        try {
            activity.preferences.edit()
                .putString(
                    RECENTLY_USED_QUICK_ACTION,
                    SendButtonAction.of(attachmentChoice).toString()
                )
                .apply()
        } catch (e: IllegalArgumentException) {
            //just do not save
        }

        val conversation = fragment.conversation!!
        val encryption = conversation.nextEncryption
        val mode = conversation.mode
        if (encryption == Message.ENCRYPTION_PGP) {
            if (hasPgp()) {
                if (mode == Conversation.MODE_SINGLE && conversation.contact.pgpKeyId != 0L) {
                    activity.xmppConnectionService.pgpEngine!!.hasKey(
                        conversation.contact,
                        object : UiCallback<Contact> {

                            override fun userInputRequried(pi: PendingIntent, contact: Contact) {
                                startPendingIntent(pi, attachmentChoice)
                            }

                            override fun success(contact: Contact) {
                                selectPresenceToAttachFile(attachmentChoice)
                            }

                            override fun error(error: Int, contact: Contact) {
                                activity.replaceToast(activity.getString(error))
                            }
                        })
                } else if (mode == Conversation.MODE_MULTI && conversation.mucOptions.pgpKeysInUse()) {
                    if (!conversation.mucOptions.everybodyHasKeys()) {
                        val warning = Toast.makeText(
                            activity,
                            R.string.missing_public_keys,
                            Toast.LENGTH_LONG
                        )
                        warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
                        warning.show()
                    }
                    selectPresenceToAttachFile(attachmentChoice)
                } else {
                    showNoPGPKeyDialog(false, DialogInterface.OnClickListener { _, _ ->
                        conversation.nextEncryption = Message.ENCRYPTION_NONE
                        activity.xmppConnectionService.updateConversation(conversation)
                        selectPresenceToAttachFile(attachmentChoice)
                    })
                }
            } else {
                showInstallPgpDialog()
            }
        } else {
            selectPresenceToAttachFile(attachmentChoice)
        }
    }
}