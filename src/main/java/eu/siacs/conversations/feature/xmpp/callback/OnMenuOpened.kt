package eu.siacs.conversations.feature.xmpp.callback

import android.support.v7.app.AppCompatDelegate
import android.view.Menu
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnMenuOpened @Inject constructor() {
    operator fun invoke(id: Int, menu: Menu?) {
        if (id == AppCompatDelegate.FEATURE_SUPPORT_ACTION_BAR && menu != null) {
            MenuDoubleTabUtil.recordMenuOpen()
        }
    }
}