package eu.siacs.conversations.feature.conversation.command

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.feature.conversation.*
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.Attachment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HandlePositiveActivityResult @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity,
    private val commitAttachments: CommitAttachments,
    private val sendMessage: SendMessage,
    private val toggleInputMethod: ToggleInputMethod
) : (Int, Intent) -> Unit {

    override fun invoke(requestCode: Int, data: Intent) {
        when (requestCode) {
            REQUEST_TRUST_KEYS_TEXT -> sendMessage()
            REQUEST_TRUST_KEYS_ATTACHMENTS -> commitAttachments()
            ATTACHMENT_CHOICE_CHOOSE_IMAGE -> {
                val imageUris = Attachment.extractAttachments(
                    activity,
                    data,
                    Attachment.Type.IMAGE
                )
                fragment.mediaPreviewAdapter!!.addMediaPreviews(imageUris)
                toggleInputMethod()
            }
            ATTACHMENT_CHOICE_TAKE_PHOTO -> {
                val takePhotoUri = fragment.pendingTakePhotoUri.pop()
                if (takePhotoUri != null) {
                    fragment.mediaPreviewAdapter!!.addMediaPreviews(
                        Attachment.of(
                            activity,
                            takePhotoUri,
                            Attachment.Type.IMAGE
                        )
                    )
                    toggleInputMethod()
                } else {
                    Log.d(Config.LOGTAG, "lost take photo uri. unable to to attach")
                }
            }
            ATTACHMENT_CHOICE_CHOOSE_FILE, ATTACHMENT_CHOICE_RECORD_VIDEO, ATTACHMENT_CHOICE_RECORD_VOICE -> {
                val type =
                    if (requestCode == ATTACHMENT_CHOICE_RECORD_VOICE) Attachment.Type.RECORDING else Attachment.Type.FILE
                val fileUris = Attachment.extractAttachments(activity, data, type)
                fragment.mediaPreviewAdapter!!.addMediaPreviews(fileUris)
                toggleInputMethod()
            }
            ATTACHMENT_CHOICE_LOCATION -> {
                val latitude = data.getDoubleExtra("latitude", 0.0)
                val longitude = data.getDoubleExtra("longitude", 0.0)
                val geo = Uri.parse("geo:$latitude,$longitude")
                fragment.mediaPreviewAdapter!!.addMediaPreviews(
                    Attachment.of(
                        activity,
                        geo,
                        Attachment.Type.LOCATION
                    )
                )
                toggleInputMethod()
            }
            XmppActivity.REQUEST_INVITE_TO_CONVERSATION -> {
                val invite = XmppActivity.ConferenceInvite.parse(data)
                if (invite != null) {
                    if (invite.execute(activity)) {
                        Toast.makeText(
                            activity,
                            R.string.creating_conference,
                            Toast.LENGTH_LONG
                        ).apply {
                            activity.mToast = this
                            show()
                        }
                    }
                }
            }
        }
        Unit
    }
}