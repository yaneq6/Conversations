package eu.siacs.conversations

import android.app.Activity
import io.aakit.BaseNavigator
import io.aakit.Navigator

fun activityNavigator(activity: Activity): ActivityNavigator = object : ActivityNavigator, Navigator by BaseNavigator(activity) {}

interface ActivityNavigator : AppNavigator

