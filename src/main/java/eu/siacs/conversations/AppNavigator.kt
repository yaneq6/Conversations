package eu.siacs.conversations

import android.content.Context
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.BaseNavigator
import io.aakit.Navigator
import io.aakit.startActivity

fun Context.appNavigator(): ActivityNavigator = object : ActivityNavigator, Navigator by BaseNavigator(this) {}

interface AppNavigator : Navigator {
    fun conversationsActivity() = startActivity<ConversationsActivity>()
}