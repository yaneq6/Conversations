package eu.siacs.conversations.feature.di

import android.app.Activity
import dagger.Module
import dagger.Provides
import eu.siacs.conversations.ActivityNavigator
import eu.siacs.conversations.activityNavigator
import io.aakit.scope.ActivityScope

@Module
class ActivityModule(
    private val activity: Activity
) {

    @Provides
    @ActivityScope
    fun activity() = activity

    @Provides
    @ActivityScope
    fun fragmentManager(activity: Activity) = activity.fragmentManager!!

    @Provides
    @ActivityScope
    fun contentResolver() = activity.contentResolver!!

    @Provides
    @ActivityScope
    fun navigator(): ActivityNavigator = activity.activityNavigator()

    @Provides
    @ActivityScope
    fun resources() = activity.resources!!

    @Provides
    @ActivityScope
    fun packageManager() = activity.packageManager
}