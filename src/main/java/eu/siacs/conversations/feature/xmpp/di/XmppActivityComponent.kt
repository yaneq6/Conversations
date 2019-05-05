package eu.siacs.conversations.feature.xmpp.di

import android.app.Activity
import dagger.Module
import dagger.Provides
import eu.siacs.conversations.feature.di.ActivityModule
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope

@Module(includes = [ActivityModule::class])
class XmppActivityModule {

    @Provides
    @ActivityScope
    fun xmppActivity(activity: Activity) = activity as XmppActivity
}