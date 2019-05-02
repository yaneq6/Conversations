package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import android.net.Uri
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.Attachment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AttachEditorContentToConversation @Inject constructor(
    private val fragment: ConversationFragment,
    private val toggleInputMethod: ToggleInputMethod,
    private val activity: Activity
) : (Uri) -> Unit {
    override fun invoke(uri: Uri) {
        fragment.mediaPreviewAdapter!!.addMediaPreviews(
            Attachment.of(
                activity,
                uri,
                Attachment.Type.FILE
            )
        )
        toggleInputMethod()
    }
}