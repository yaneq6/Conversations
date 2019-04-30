package eu.siacs.conversations.feature.conversations

import android.support.v7.app.ActionBar
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.utils.EmojiWrapper

class InvalidateActionBarTitleCommand(
    private val activity: ConversationsActivity
) : () -> Unit {
    override fun invoke() {
        activity.supportActionBar?.invalidateTitle()

        activity.supportActionBar?.run {
            activity.fragmentManager
                .findFragmentById(R.id.main_fragment)
                .let { it as? ConversationFragment }
                ?.let { mainFragment ->
                    mainFragment.conversation?.let { conversation ->
                        title = EmojiWrapper.transform(conversation.name)
                        setDisplayHomeAsUpEnabled(true)
                    }
                }
                ?: run {
                    setTitle(R.string.app_name)
                    setDisplayHomeAsUpEnabled(false)
                }
        }
    }

    private fun ActionBar.invalidateTitle() =
        activity.fragmentManager.findFragmentById(R.id.main_fragment).let { fragment ->
            when (fragment is ConversationFragment) {
                true -> setFragmentTitle(fragment)
                false -> setAppTitle()
            }
        }

    private fun ActionBar.setFragmentTitle(fragment: ConversationFragment) {
        fragment.conversation?.let { conversation ->
            title = EmojiWrapper.transform(conversation.name)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun ActionBar.setAppTitle() {
        setTitle(R.string.app_name)
        setDisplayHomeAsUpEnabled(false)
    }
}