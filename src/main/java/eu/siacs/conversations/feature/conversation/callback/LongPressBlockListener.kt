package eu.siacs.conversations.feature.conversation.callback

import android.view.View
import eu.siacs.conversations.feature.conversation.command.ShowBlockSubmenu
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class LongPressBlockListener @Inject constructor(
    private val showBlockSubmenu: ShowBlockSubmenu
) : View.OnLongClickListener {

    override fun onLongClick(view: View): Boolean = showBlockSubmenu(view)
}