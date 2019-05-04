package eu.siacs.conversations.feature.conversation.command

import android.content.Context
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class OnAttach @Inject constructor() {

    operator fun invoke(activity: Context): ConversationsActivity {
        //        super.onAttach(activity)
        Timber.d("ConversationFragment.onAttach()")
        activity as? ConversationsActivity
            ?: throw IllegalStateException("Trying to attach fragment to activity that is not the ConversationsActivity")
        return activity
    }
}