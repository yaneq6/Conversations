package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class OnStart @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val reInit: ReInit,
    private val processExtras: ProcessExtras,
    private val findAndReInitByUuidOrArchive: FindAndReInitByUuidOrArchive
) {

    operator fun invoke() {
        val conversation = fragment.conversation ?: return
        if (fragment.reInitRequiredOnStart) {
            val extras = fragment.pendingExtras.pop()
            reInit(conversation, extras != null)
            if (extras != null) {
                processExtras(extras)
            }
        } else if (activity.xmppConnectionService != null) {
            val uuid: String? = fragment.pendingConversationsUuid.pop()
            Timber.e("ConversationFragment.onStart() - activity was bound but no conversation loaded. uuid=$uuid")
            uuid?.let(findAndReInitByUuidOrArchive)
        }
    }
}