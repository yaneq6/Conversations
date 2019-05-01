package eu.siacs.conversations

import android.app.Activity
import io.aakit.BaseNavigator
import io.aakit.Navigator

fun Activity.activityNavigator(): ActivityNavigator = object : ActivityNavigator, Navigator by BaseNavigator(this) {}

interface ActivityNavigator : AppNavigator

