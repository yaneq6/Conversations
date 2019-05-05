package eu.siacs.conversations.feature.xmpp.query

import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class GetThemeResource @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(r_attr_name: Int, r_drawable_def: Int): Int {
        val attrs = intArrayOf(r_attr_name)
        val ta = activity.theme.obtainStyledAttributes(attrs)

        val res = ta.getResourceId(0, r_drawable_def)
        ta.recycle()

        return res
    }
}