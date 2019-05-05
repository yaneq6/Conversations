package eu.siacs.conversations.feature.conversation.di

import android.app.Activity
import dagger.Component
import dagger.Module
import dagger.Provides
import eu.siacs.conversations.feature.xmpp.di.XmppActivityModule
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope

@ActivityScope
@Component(modules = [ConversationModule::class])
interface ConversationComponent : (ConversationFragment) -> ConversationFragment

@Module(includes = [XmppActivityModule::class])
class ConversationModule(
    private val fragment: ConversationFragment
) {

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