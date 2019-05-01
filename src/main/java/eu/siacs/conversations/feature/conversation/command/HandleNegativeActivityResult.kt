package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class HandleNegativeActivityResult @Inject constructor(
    private val fragment: ConversationFragment
): (Int) -> Unit {
    override fun invoke(requestCode: Int) {
        when (requestCode) {
            ConversationFragment.ATTACHMENT_CHOICE_TAKE_PHOTO -> if (fragment.pendingTakePhotoUri.clear()) {
                Timber.d("cleared pending photo uri after negative activity result")
            }
        }
    }
}