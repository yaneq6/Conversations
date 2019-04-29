package eu.siacs.conversations

import android.app.Activity
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.BaseNavigator
import io.aakit.Navigator
import io.aakit.startActivity

fun appNavigator(activity: Activity): ActivityNavigator = object : ActivityNavigator, Navigator by BaseNavigator(activity) {}

interface AppNavigator : Navigator {
    fun conversationsActivity() = startActivity<ConversationsActivity>()
}