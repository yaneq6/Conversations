package eu.siacs.conversations.feature.conversations.di

import android.app.Activity
import android.content.Intent
import dagger.Component
import dagger.Module
import dagger.Provides
import eu.siacs.conversations.ActivityNavigator
import eu.siacs.conversations.activityNavigator
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.util.PendingItem
import io.aakit.scope.ActivityScope

import java.util.concurrent.atomic.AtomicBoolean

@ActivityScope
@Component(modules = [ConversationsModule::class])
interface ConversationsComponent : (ConversationsActivity) -> ConversationsActivity

@Module(includes = [ActivityModule::class])
class ConversationsModule {

    @Provides
    @ActivityScope
    fun conversationsActivity(activity: Activity) = activity as ConversationsActivity

    @Provides
    @ActivityScope
    fun pendingViewIntent() = PendingItem<Intent>()

    @Provides
    @ActivityScope
    fun redirectInProcess() = AtomicBoolean()
}

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
}