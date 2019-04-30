package eu.siacs.conversations.feature.conversations

import android.app.FragmentManager
import android.support.annotation.IdRes
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.ActivityConversationsBinding
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsOverviewFragment
import eu.siacs.conversations.ui.XmppFragment
import eu.siacs.conversations.ui.interfaces.OnBackendConnected

class XmppFragmentsInteractor(
    private val fragmentManager: FragmentManager
) {

    fun initialize(binding: ActivityConversationsBinding) {
        var transaction = fragmentManager.beginTransaction()
        val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
        val secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment)
        if (mainFragment != null) {
            if (binding.secondaryFragment != null) {
                if (mainFragment is ConversationFragment) {
                    fragmentManager.popBackStack()
                    transaction.remove(mainFragment)
                    transaction.commit()
                    fragmentManager.executePendingTransactions()
                    transaction = fragmentManager.beginTransaction()
                    transaction.replace(R.id.secondary_fragment, mainFragment)
                    transaction.replace(
                        R.id.main_fragment,
                        ConversationsOverviewFragment()
                    )
                    transaction.commit()
                    return
                }
            } else {
                if (secondaryFragment is ConversationFragment) {
                    transaction.remove(secondaryFragment)
                    transaction.commit()
                    fragmentManager.executePendingTransactions()
                    transaction = fragmentManager.beginTransaction()
                    transaction.replace(R.id.main_fragment, secondaryFragment)
                    transaction.addToBackStack(null)
                    transaction.commit()
                    return
                }
            }
        } else {
            transaction.replace(
                R.id.main_fragment,
                ConversationsOverviewFragment()
            )
        }
        if (binding.secondaryFragment != null && secondaryFragment == null) {
            transaction.replace(
                R.id.secondary_fragment,
                ConversationFragment()
            )
        }
        transaction.commit()
    }


    fun refresh() = refresh(*FRAGMENT_ID_NOTIFICATION_ORDER)

    fun refresh(@IdRes vararg ids: Int) = ids
        .map(fragmentManager::findFragmentById)
        .filterIsInstance<XmppFragment>()
        .forEach(XmppFragment::refresh)


    fun onBackendConnected(@IdRes id: Int) {
        fragmentManager.findFragmentById(id).let { it as? OnBackendConnected }?.onBackendConnected()
    }


    companion object {
        @IdRes
        private val FRAGMENT_ID_NOTIFICATION_ORDER = intArrayOf(
            R.id.secondary_fragment,
            R.id.main_fragment
        )
    }
}