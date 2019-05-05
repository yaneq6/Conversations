package eu.siacs.conversations.feature.xmpp.query

import eu.siacs.conversations.R
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UsingEnterKey @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference("display_enter_key",
            R.bool.display_enter_key
        )
    }
}