package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class FindAndReInitByUuidOrArchive @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val clearPending: ClearPending,
    private val reInit: ReInit,
    private val setScrollPosition: SetScrollPosition,
    private val toggleInputMethod: ToggleInputMethod
) : (String) -> Boolean {

    override fun invoke(uuid: String): Boolean {
        val conversation = activity.xmppConnectionService.findConversationByUuid(uuid)
        if (conversation == null) {
            clearPending()
            return false
        }
        reInit(conversation)
        val scrollState = fragment.pendingScrollState.pop()
        val lastMessageUuid = fragment.pendingLastMessageUuid.pop()
        val attachments = fragment.pendingMediaPreviews.pop()
        if (scrollState != null) {
            setScrollPosition(scrollState, lastMessageUuid)
        }
        if (attachments != null && attachments.size > 0) {
            Timber.d("had attachments on restore")
            fragment.mediaPreviewAdapter!!.addMediaPreviews(attachments)
            toggleInputMethod()
        }
        return true
    }
}