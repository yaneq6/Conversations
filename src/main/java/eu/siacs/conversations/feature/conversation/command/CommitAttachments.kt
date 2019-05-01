package eu.siacs.conversations.feature.conversation.command

import android.Manifest
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
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
    private val activity: XmppActivity
) : () -> Unit {

    override fun invoke() = fragment.run {
        if (!hasPermissions(
                ConversationFragment.REQUEST_COMMIT_ATTACHMENTS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )) {
            return
        }
        if (conversation!!.nextEncryption == Message.ENCRYPTION_AXOLOTL && trustKeysIfNeeded(
                ConversationFragment.REQUEST_TRUST_KEYS_ATTACHMENTS
            )
        ) {
            return
        }
        val attachments = mediaPreviewAdapter!!.attachments
        val callback = PresenceSelector.OnPresenceSelected {
            val i = attachments.iterator()
            while (i.hasNext()) {
                val attachment = i.next()
                if (attachment.type == Attachment.Type.LOCATION) {
                    attachLocationToConversation(conversation, attachment.uri)
                } else if (attachment.type == Attachment.Type.IMAGE) {
                    Timber.d("ConversationsActivity.commitAttachments() - attaching image to conversations. CHOOSE_IMAGE")
                    attachImageToConversation(conversation, attachment.uri)
                } else {
                    Timber.d("ConversationsActivity.commitAttachments() - attaching file to conversations. CHOOSE_FILE/RECORD_VOICE/RECORD_VIDEO")
                    attachFileToConversation(conversation, attachment.uri, attachment.mime)
                }
                i.remove()
            }
            mediaPreviewAdapter!!.notifyDataSetChanged()
            toggleInputMethod()
        }
        if (conversation == null || conversation!!.mode == Conversation.MODE_MULTI || FileBackend.allFilesUnderSize(
                getActivity(),
                attachments,
                getMaxHttpUploadSize(conversation!!)
            )
        ) {
            callback.onPresenceSelected()
        } else {
            this@CommitAttachments.activity.selectPresence(conversation, callback)
        }
    }
}