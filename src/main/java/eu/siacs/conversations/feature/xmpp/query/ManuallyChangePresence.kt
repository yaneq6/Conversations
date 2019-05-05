package eu.siacs.conversations.feature.xmpp.query

import eu.siacs.conversations.R
import eu.siacs.conversations.feature.xmpp.query.GetBooleanPreference
import eu.siacs.conversations.ui.SettingsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ManuallyChangePresence @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference(
            SettingsActivity.MANUALLY_CHANGE_PRESENCE,
            R.bool.manually_change_presence
        )
    }
}