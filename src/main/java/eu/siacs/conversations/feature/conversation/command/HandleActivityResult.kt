package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import eu.siacs.conversations.ui.util.ActivityResult
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HandleActivityResult @Inject constructor(
    private val handlePositiveActivityResult: HandlePositiveActivityResult,
    private val handleNegativeActivityResult: HandleNegativeActivityResult
) : (ActivityResult) -> Unit {
    override fun invoke(activityResult: ActivityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data)
        } else {
            handleNegativeActivityResult(activityResult.requestCode)
        }
    }
}