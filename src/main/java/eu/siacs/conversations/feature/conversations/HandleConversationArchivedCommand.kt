package eu.siacs.conversations.feature.conversations

import android.app.FragmentManager
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.ConversationsOverviewFragment
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class HandleConversationArchivedCommand @Inject constructor(
    private val activity: ConversationsActivity,
    private val performRedirectIfNecessary: PerformRedirectIfNecessaryCommand,
    private val openConversation: OpenConversationCommand,
    private val fragmentManager: FragmentManager
) : (Conversation) -> Unit {

    override fun invoke(conversation: Conversation) {
        if (performRedirectIfNecessary(false, conversation)) {
            return
        }
        val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
        if (mainFragment is ConversationFragment) {
            try {
                fragmentManager.popBackStack()
            } catch (e: IllegalStateException) {
                Timber.w(e, "state loss while popping back state after archiving conversation")
                //this usually means activity is no longer active; meaning on the next open we will run through this again
            }

            return
        }
        val secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment)
        if (secondaryFragment is ConversationFragment) {
            if (secondaryFragment.conversation === conversation) {
                val suggestion =
                    ConversationsOverviewFragment.getSuggestion(activity, conversation)
                if (suggestion != null) {
                    openConversation(suggestion)
                }
            }
        }
    }
}