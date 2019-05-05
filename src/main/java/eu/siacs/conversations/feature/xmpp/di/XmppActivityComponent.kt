package eu.siacs.conversations.feature.xmpp.di

import android.app.Activity
import dagger.Component
import dagger.Module
import dagger.Provides
import eu.siacs.conversations.feature.di.ActivityModule
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope

@ActivityScope
@Component(modules = [XmppActivityModule::class])
interface XmppActivityComponent : (XmppActivity) -> XmppActivity

@Module(includes = [ActivityModule::class])
class XmppActivityModule {

    @Provides
    @ActivityScope
    fun xmppActivity(activity: Activity) = activity as XmppActivity
}