package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import android.content.Intent
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.util.ActivityResult
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HandleActivityResult @Inject constructor(
        private val activity: ConversationsActivity,
        private val handlePositiveActivityResult: HandlePositiveActivityResult,
        private val handleNegativeActivityResult: HandleNegativeActivityResult
) {
    operator fun invoke(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
        val activityResult = ActivityResult.of(requestCode, resultCode, data)
        if (activity.xmppConnectionService != null) {
            invoke(activityResult)
        } else {
            activity.postponedActivityResult.push(activityResult)
        }
    }

    operator fun invoke(activityResult: ActivityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data)
        } else {
            handleNegativeActivityResult(activityResult.requestCode)
        }
    }
}