package eu.siacs.conversations.feature.conversation.di

import android.app.Fragment
import dagger.Component
import dagger.Module
import dagger.Provides
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.feature.di.FragmentModule
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope

@Module(includes = [FragmentModule::class])
class ConversationFragmentModule {

    @Provides
    @ActivityScope
    fun Fragment.conversationFragment() = this as ConversationFragment

    @Provides
    @ActivityScope
    fun ConversationFragment.binding(): FragmentConversationBinding = binding!!

}


@ActivityScope
@Component(modules = [ConversationFragmentModule::class])
interface ConversationFragmentComponent {

//    val reInit: ReInit

}