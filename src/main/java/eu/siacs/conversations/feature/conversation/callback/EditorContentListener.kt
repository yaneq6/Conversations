package eu.siacs.conversations.feature.conversation.callback

import android.Manifest
import android.os.Bundle
import android.support.v13.view.inputmethod.InputConnectionCompat
import android.support.v13.view.inputmethod.InputContentInfoCompat
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.feature.conversation.REQUEST_ADD_EDITOR_CONTENT
import eu.siacs.conversations.feature.conversation.command.AttachEditorContentToConversation
import eu.siacs.conversations.feature.conversation.command.HasPermissions
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.widget.EditMessage
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class EditorContentListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val hasPermissions: HasPermissions,
    private val attachEditorContentToConversation: AttachEditorContentToConversation
) : EditMessage.OnCommitContentListener {

    override fun onCommitContent(
        inputContentInfo: InputContentInfoCompat,
        flags: Int,
        opts: Bundle?,
        mimeTypes: Array<out String>?
    ): Boolean {
        // try to get permission to read the image, if applicable
        if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
            try {
                inputContentInfo.requestPermission()
            } catch (e: Exception) {
                Timber.e(e, "InputContentInfoCompat#requestPermission() failed.")
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.no_permission_to_access_x,
                        inputContentInfo.description
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return false
            }

        }
        if (hasPermissions(
                REQUEST_ADD_EDITOR_CONTENT,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            attachEditorContentToConversation(inputContentInfo.contentUri)
        } else {
            fragment.pendingEditorContent = inputContentInfo.contentUri
        }
        return true
    }
}