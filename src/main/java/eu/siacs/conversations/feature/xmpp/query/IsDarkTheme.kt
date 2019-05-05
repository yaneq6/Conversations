package eu.siacs.conversations.feature.xmpp.query

import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.utils.ThemeHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class IsDarkTheme @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(): Boolean =
        ThemeHelper.isDark(activity.mTheme)
}