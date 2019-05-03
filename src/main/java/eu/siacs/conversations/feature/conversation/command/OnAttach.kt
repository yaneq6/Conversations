package eu.siacs.conversations.feature.conversation.command

import android.content.Context
import eu.siacs.conversations.feature.conversation.di.ConversationModule
import eu.siacs.conversations.feature.conversation.di.DaggerConversationComponent
import eu.siacs.conversations.feature.conversations.di.ActivityModule
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class OnAttach @Inject constructor(
    private val fragment: ConversationFragment
) {

    operator fun invoke(activity: Context): ConversationsActivity {
        //        super.onAttach(activity)
        Timber.d("ConversationFragment.onAttach()")
        activity as? ConversationsActivity
            ?: throw IllegalStateException("Trying to attach fragment to activity that is not the ConversationsActivity")
        DaggerConversationComponent.builder()
            .activityModule(ActivityModule(activity))
            .conversationModule(ConversationModule(fragment))
            .build()(fragment)
        return activity
    }
}