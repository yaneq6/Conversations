package eu.siacs.conversations.feature.conversation.command

import android.content.Intent
import android.provider.MediaStore
import eu.siacs.conversations.R
import eu.siacs.conversations.feature.conversation.*
import eu.siacs.conversations.feature.xmpp.command.SelectPresence
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.RecordingActivity
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.PresenceSelector
import eu.siacs.conversations.utils.GeoHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SelectPresenceToAttachFile @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity,
    private val selectPresence: SelectPresence
) : (Int) -> Unit {

    override fun invoke(attachmentChoice: Int) {
        val conversation = fragment.conversation!!
        val account = conversation.account
        val callback = PresenceSelector.OnPresenceSelected {
            var chooser = false
            val intent = when (attachmentChoice) {
                ATTACHMENT_CHOICE_CHOOSE_IMAGE -> Intent().apply {
                    action = Intent.ACTION_GET_CONTENT
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    type = "image/*"
                    chooser = true
                }
                ATTACHMENT_CHOICE_RECORD_VIDEO -> Intent().apply {
                    action = MediaStore.ACTION_VIDEO_CAPTURE
                }
                ATTACHMENT_CHOICE_TAKE_PHOTO -> Intent().apply {
                    val uri = activity.xmppConnectionService.fileBackend.takePhotoUri
                    fragment.pendingTakePhotoUri.push(uri)
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    action = MediaStore.ACTION_IMAGE_CAPTURE
                }
                ATTACHMENT_CHOICE_CHOOSE_FILE -> Intent().apply {
                    chooser = true
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    action = Intent.ACTION_GET_CONTENT
                }
                ATTACHMENT_CHOICE_RECORD_VOICE -> Intent(activity, RecordingActivity::class.java)
                ATTACHMENT_CHOICE_LOCATION -> GeoHelper.getFetchIntent(activity)
                else -> Intent()
            }
            if (intent.resolveActivity(activity.packageManager) != null) {
                if (chooser) {
                    fragment.startActivityForResult(
                        Intent.createChooser(
                            intent,
                            activity.getString(R.string.perform_action_with)
                        ),
                        attachmentChoice
                    )
                } else {
                    fragment.startActivityForResult(intent, attachmentChoice)
                }
            }
        }
        if (account.httpUploadAvailable() || attachmentChoice == ATTACHMENT_CHOICE_LOCATION) {
            conversation.nextCounterpart = null
            callback.onPresenceSelected()
        } else {
            selectPresence(conversation, callback)
        }
    }
}