package eu.siacs.conversations.di

import android.app.Activity
import dagger.Component
import dagger.Module
import dagger.Provides
import eu.siacs.conversations.activityNavigator
import io.aakit.scope.ActivityScope
import javax.inject.Singleton


@Singleton
@Module
class AppModule


@Module
class ActivityModule(
    val activity: Activity
) {

    @Provides
    @ActivityScope
    fun activity() = activity

    @Provides
    @ActivityScope
    fun navigator() = activityNavigator(activity)
}


@Singleton
@Component(
    modules = [AppModule::class]
)
class AppComponent {


}