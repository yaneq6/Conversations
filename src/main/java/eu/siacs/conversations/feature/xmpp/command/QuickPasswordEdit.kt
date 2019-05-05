package eu.siacs.conversations.feature.xmpp.command

import eu.siacs.conversations.R
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class QuickPasswordEdit @Inject constructor(
    private val quickEdit: QuickEdit
) {
    operator fun invoke(previousValue: String, onValueEdited: (String) -> String?) {
        quickEdit(previousValue, onValueEdited,
            R.string.password, password = true, permitEmpty = false)
    }

}