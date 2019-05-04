package eu.siacs.conversations.feature.conversation.command

import android.view.ContextMenu
import android.view.View
import android.widget.AdapterView
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnCreateContextMenu @Inject constructor(
    private val fragment: ConversationFragment,
    private val populateContextMenu: PopulateContextMenu
) {
    operator fun invoke(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo) {
        synchronized(fragment.messageList) {
            val acmi = menuInfo as AdapterView.AdapterContextMenuInfo
            fragment.selectedMessage = fragment.messageList[acmi.position]
            populateContextMenu(menu)
        }
    }
}