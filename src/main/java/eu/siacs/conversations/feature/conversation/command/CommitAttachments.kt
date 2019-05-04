package eu.siacs.conversations.feature.conversation.command

import android.Manifest
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.conversation.REQUEST_COMMIT_ATTACHMENTS
import eu.siacs.conversations.feature.conversation.REQUEST_TRUST_KEYS_ATTACHMENTS
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.ui.util.PresenceSelector
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class CommitAttachments @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: XmppActivity,
    private val getMaxHttpUploadSize: GetMaxHttpUploadSize,
    private val hasPermissions: HasPermissions,
    private val trustKeysIfNeeded: TrustKeysIfNeeded,
    private val attachLocationToConversation: AttachLocationToConversation,
    private val attachImageToConversation: AttachImageToConversation,
    private val attachFileToConversation: AttachFileToConversation,
    private val toggleInputMethod: ToggleInputMethod
) : () -> Unit {

    override fun invoke() {
        val conversation = fragment.conversation
        if (!hasPermissions(
                REQUEST_COMMIT_ATTACHMENTS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )) {
            return
        }
        if (conversation!!.nextEncryption == Message.ENCRYPTION_AXOLOTL && trustKeysIfNeeded(
                REQUEST_TRUST_KEYS_ATTACHMENTS
            )
        ) {
            return
        }
        val mediaPreviewAdapter = fragment.mediaPreviewAdapter!!
        val attachments = mediaPreviewAdapter.attachments
        val callback = PresenceSelector.OnPresenceSelected {
            val i = attachments.iterator()
            while (i.hasNext()) {
                val attachment = i.next()
                when {
                    attachment.type == Attachment.Type.LOCATION -> attachLocationToConversation(conversation, attachment.uri)
                    attachment.type == Attachment.Type.IMAGE -> {
                        Timber.d("ConversationsActivity.commitAttachments() - attaching image to conversations. CHOOSE_IMAGE")
                        attachImageToConversation(conversation, attachment.uri)
                    }
                    else -> {
                        Timber.d("ConversationsActivity.commitAttachments() - attaching file to conversations. CHOOSE_FILE/RECORD_VOICE/RECORD_VIDEO")
                        attachFileToConversation(conversation, attachment.uri, attachment.mime)
                    }
                }
                i.remove()
            }
            mediaPreviewAdapter.notifyDataSetChanged()
            toggleInputMethod()
        }
        if (conversation.mode == Conversation.MODE_MULTI || FileBackend.allFilesUnderSize(
                activity,
                attachments,
                getMaxHttpUploadSize(conversation)
            )
        ) {
            callback.onPresenceSelected()
        } else {
            activity.selectPresence(conversation, callback)
        }
    }
}