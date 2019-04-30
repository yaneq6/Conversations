package eu.siacs.conversations.feature.conversations

import android.app.Activity
import android.app.FragmentManager
import android.os.Bundle
import android.support.annotation.IdRes
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment

class OpenConversationCommand(
    private val activity: Activity,
    private val fragmentsInteractor: XmppFragmentsInteractor,
    private val invalidateActionBarTitle: InvalidateActionBarTitleCommand
) :
        (Conversation, Bundle) -> Unit,
        (Conversation) -> Unit {

    override fun invoke(conversation: Conversation) = invoke(conversation, Bundle())

    override fun invoke(conversation: Conversation, extras: Bundle) {
        activity.fragmentManager?.refresh(conversation, extras)
    }

    private fun FragmentManager.refresh(conversation: Conversation, extras: Bundle) {

        fun FragmentManager.refreshSecondaryFragment() =
            findConversationFragment(R.id.secondary_fragment)?.run {
                reInit(conversation, extras)
                fragmentsInteractor.refresh(R.id.main_fragment)
            }

        fun FragmentManager.refreshMainFragment() =
            findConversationFragment(R.id.main_fragment)?.run {
                reInit(conversation, extras)
                invalidateActionBarTitle()
            }

        fun FragmentManager.startConversationFragment() {
            ConversationFragment().apply {
                val fragmentTransaction = beginTransaction()
                fragmentTransaction.replace(R.id.main_fragment, this)
                fragmentTransaction.addToBackStack(null)
                try {
                    fragmentTransaction.commit()
                } catch (e: IllegalStateException) {
                    // Leave it unhandled to figure out if it really occurs after refactor & rewrite
                    throw StateLossException()
                }
                reInit(conversation, extras)
                invalidateActionBarTitle()
            }
        }

        refreshSecondaryFragment() ?: refreshMainFragment() ?: startConversationFragment()
    }


    //allowing state loss is probably fine since view intents et all are already stored and a click can probably be 'ignored'
    class StateLossException : Exception("state loss while opening conversation")
}

private fun FragmentManager.findConversationFragment(@IdRes id: Int) = findFragmentById(id) as? ConversationFragment