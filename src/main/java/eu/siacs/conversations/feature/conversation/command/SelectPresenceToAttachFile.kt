package eu.siacs.conversations.feature.conversation.command

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.RecordingActivity
import eu.siacs.conversations.ui.util.PresenceSelector
import eu.siacs.conversations.utils.GeoHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class SelectPresenceToAttachFile @Inject constructor(
    private val fragment: ConversationFragment
) : (Int) -> Unit {
    override fun invoke(attachmentChoice: Int) = fragment.run {
        val account = conversation!!.account
        val callback = PresenceSelector.OnPresenceSelected {
            var intent = Intent()
            var chooser = false
            when (attachmentChoice) {
                ConversationFragment.ATTACHMENT_CHOICE_CHOOSE_IMAGE -> {
                    intent.action = Intent.ACTION_GET_CONTENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    intent.type = "image/*"
                    chooser = true
                }
                ConversationFragment.ATTACHMENT_CHOICE_RECORD_VIDEO -> intent.action =
                    MediaStore.ACTION_VIDEO_CAPTURE
                ConversationFragment.ATTACHMENT_CHOICE_TAKE_PHOTO -> {
                    val uri = activity!!.xmppConnectionService.fileBackend.takePhotoUri
                    pendingTakePhotoUri.push(uri)
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.action = MediaStore.ACTION_IMAGE_CAPTURE
                }
                ConversationFragment.ATTACHMENT_CHOICE_CHOOSE_FILE -> {
                    chooser = true
                    intent.type = "*/*"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    intent.action = Intent.ACTION_GET_CONTENT
                }
                ConversationFragment.ATTACHMENT_CHOICE_RECORD_VOICE -> intent =
                    Intent(getActivity(), RecordingActivity::class.java)
                ConversationFragment.ATTACHMENT_CHOICE_LOCATION -> intent =
                    GeoHelper.getFetchIntent(activity)
            }
            if (intent.resolveActivity(getActivity().packageManager) != null) {
                if (chooser) {
                    startActivityForResult(
                        Intent.createChooser(
                            intent,
                            getString(R.string.perform_action_with)
                        ),
                        attachmentChoice
                    )
                } else {
                    startActivityForResult(intent, attachmentChoice)
                }
            }
        }
        if (account.httpUploadAvailable() || attachmentChoice == ConversationFragment.ATTACHMENT_CHOICE_LOCATION) {
            conversation!!.nextCounterpart = null
            callback.onPresenceSelected()
        } else {
            activity!!.selectPresence(conversation, callback)
        }
    }
}