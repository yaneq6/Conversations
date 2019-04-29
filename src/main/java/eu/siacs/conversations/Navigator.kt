package eu.siacs.conversations

import android.app.Activity
import eu.siacs.conversations.ui.ConversationActivity
import io.aakit.BaseNavigator
import io.aakit.Navigator
import io.aakit.startActivity

interface ActivityNavigator : Navigator {
    fun conversationsActivity() = startActivity<ConversationActivity>()
}

fun activityNavigator(activity: Activity): ActivityNavigator = object : ActivityNavigator, Navigator by BaseNavigator(activity) {}