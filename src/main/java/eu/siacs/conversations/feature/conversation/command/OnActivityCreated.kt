package eu.siacs.conversations.feature.conversation.command

import android.net.Uri
import android.os.Bundle
import eu.siacs.conversations.feature.conversation.*
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.utils.QuickLoader
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnActivityCreated @Inject constructor(
    private val fragment: ConversationFragment
) {
    operator fun invoke(state: Bundle?) {
        state ?: return

        fragment.pendingLastMessageUuid.push(state.getString(STATE_LAST_MESSAGE_UUID, null))

        state.getString(STATE_CONVERSATION_UUID)?.let { uuid ->
            QuickLoader.set(uuid)
            fragment.pendingConversationsUuid.push(uuid)

            state.getParcelableArrayList<Attachment>(STATE_MEDIA_PREVIEWS)
                ?.takeIf(Collection<*>::isNotEmpty)
                ?.let(fragment.pendingMediaPreviews::push)

            state.getString(STATE_PHOTO_URI)
                ?.let(Uri::parse)
                ?.let(fragment.pendingTakePhotoUri::push)

            fragment.pendingScrollState.push(state.getParcelable(STATE_SCROLL_POSITION))
        }
    }
}