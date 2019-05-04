package eu.siacs.conversations.feature.conversation.command

import android.os.Bundle
import eu.siacs.conversations.feature.conversation.*
import eu.siacs.conversations.feature.conversation.query.GetScrollPosition
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import java.util.ArrayList
import javax.inject.Inject

@ActivityScope
class OnSaveInstanceState @Inject constructor(
    private val fragment: ConversationFragment,
    private val getScrollPosition: GetScrollPosition
) {
    operator fun invoke(outState: Bundle) {
        fragment.conversation?.let { conversation ->
            outState.putString(STATE_CONVERSATION_UUID, conversation.uuid)
            outState.putString(STATE_LAST_MESSAGE_UUID, fragment.lastMessageUuid)
            val uri = fragment.pendingTakePhotoUri.peek()
            if (uri != null) {
                outState.putString(STATE_PHOTO_URI, uri.toString())
            }
            val scrollState = getScrollPosition()
            if (scrollState != null) {
                outState.putParcelable(STATE_SCROLL_POSITION, scrollState)
            }
            val attachments =
                if (fragment.mediaPreviewAdapter == null) ArrayList() else fragment.mediaPreviewAdapter!!.attachments
            if (attachments.size > 0) {
                outState.putParcelableArrayList(
                    STATE_MEDIA_PREVIEWS,
                    attachments
                )
            }
        }
    }
}