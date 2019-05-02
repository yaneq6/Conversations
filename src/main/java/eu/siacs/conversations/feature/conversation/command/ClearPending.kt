package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class ClearPending @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() = fragment.run {
        if (postponedActivityResult.clear()) {
            Timber.e("cleared pending intent with unhandled result left")
        }
        if (pendingScrollState.clear()) {
            Timber.e("cleared scroll state")
        }
        if (pendingTakePhotoUri.clear()) {
            Timber.e("cleared pending photo uri")
        }
        if (pendingConversationsUuid.clear()) {
            Timber.e("cleared pending conversations uuid")
        }
        if (pendingMediaPreviews.clear()) {
            Timber.e("cleared pending media previews")
        }
    }
}