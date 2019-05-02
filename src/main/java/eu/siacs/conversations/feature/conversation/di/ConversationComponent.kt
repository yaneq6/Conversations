package eu.siacs.conversations.feature.conversation.di

import android.app.Activity
import dagger.Component
import dagger.Module
import dagger.Provides
import eu.siacs.conversations.feature.conversations.di.ActivityModule
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope

@ActivityScope
@Component(modules = [ConversationModule::class])
interface ConversationComponent : (ConversationFragment) -> ConversationFragment

@Module(includes = [ActivityModule::class])
class ConversationModule(
    private val fragment: ConversationFragment
) {

    @Provides
    @ActivityScope
    fun xmppActivity(activity: Activity) = activity as XmppActivity


    @Provides
    @ActivityScope
    fun conversatinsActivity(activity: Activity) = activity as ConversationsActivity


    @Provides
    @ActivityScope
    fun conversationFragment() = fragment

    @Provides
    @ActivityScope
    fun fragmentConversationBinding() = fragment.binding!!
}