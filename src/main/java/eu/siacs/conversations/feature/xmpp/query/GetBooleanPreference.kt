package eu.siacs.conversations.feature.xmpp.query

import android.content.res.Resources
import android.support.annotation.BoolRes
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class GetBooleanPreference @Inject constructor(
    private val activity: XmppActivity,
    private val resources: Resources
) {
    operator fun invoke(name: String, @BoolRes res: Int): Boolean {
        return activity.preferences.getBoolean(name, resources.getBoolean(res))
    }
}