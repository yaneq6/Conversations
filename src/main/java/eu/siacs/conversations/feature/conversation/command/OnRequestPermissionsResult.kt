package eu.siacs.conversations.feature.conversation.command

import android.Manifest
import android.support.annotation.StringRes
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.feature.conversation.REQUEST_ADD_EDITOR_CONTENT
import eu.siacs.conversations.feature.conversation.REQUEST_COMMIT_ATTACHMENTS
import eu.siacs.conversations.feature.conversation.REQUEST_START_DOWNLOAD
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.utils.PermissionUtils
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnRequestPermissionsResult @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val startDownloadable: StartDownloadable,
    private val attachEditorContentToConversation: AttachEditorContentToConversation,
    private val commitAttachments: CommitAttachments,
    private val attachFile: AttachFile
) {

    operator fun invoke(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty()) {
            if (PermissionUtils.allGranted(grantResults)) when (requestCode) {
                REQUEST_START_DOWNLOAD -> fragment.pendingDownloadableMessage?.let(
                    startDownloadable
                )
                REQUEST_ADD_EDITOR_CONTENT -> fragment.pendingEditorContent?.let(
                    attachEditorContentToConversation
                )
                REQUEST_COMMIT_ATTACHMENTS -> commitAttachments()
                else -> attachFile(requestCode)
            } else {
                @StringRes val res: Int =
                    when (PermissionUtils.getFirstDenied(
                        grantResults,
                        permissions
                    )) {
                        Manifest.permission.RECORD_AUDIO -> R.string.no_microphone_permission
                        Manifest.permission.CAMERA -> R.string.no_camera_permission
                        else -> R.string.no_storage_permission
                    }
                Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()
            }
        }
        if (PermissionUtils.writeGranted(grantResults, permissions)) {
            activity.xmppConnectionService?.restartFileObserver()
            fragment.refresh()
        }
    }
}