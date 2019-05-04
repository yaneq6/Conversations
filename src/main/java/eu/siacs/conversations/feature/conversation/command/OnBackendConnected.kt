package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class OnBackendConnected @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val findAndReInitByUuidOrArchive: FindAndReInitByUuidOrArchive,
    private val clearPending: ClearPending,
    private val handleActivityResult: HandleActivityResult
) {
    operator fun invoke() {
        Timber.d("ConversationFragment.onBackendConnected()")
        val uuid = fragment.pendingConversationsUuid.pop()
        val conversation = fragment.conversation
        if (uuid != null) {
            if (!findAndReInitByUuidOrArchive(uuid)) {
                return
            }
        } else {
            if (!activity.xmppConnectionService.isConversationStillOpen(conversation)) {
                clearPending()
                activity.onConversationArchived(conversation!!)
                return
            }
        }
        val activityResult = fragment.postponedActivityResult.pop()
        if (activityResult != null) {
            handleActivityResult(activityResult)
        }
        clearPending()
    }
}