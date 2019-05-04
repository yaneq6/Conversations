package eu.siacs.conversations.feature.di

import android.app.Fragment
import dagger.Module
import dagger.Provides
import io.aakit.scope.ActivityScope

@Module
class FragmentModule(
    private val fragment: Fragment
) {

    @Provides
    @ActivityScope
    fun fragment() = fragment
}