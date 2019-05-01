package eu.siacs.conversations.feature.conversations

import android.content.pm.PackageManager
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.ConversationsActivity.Companion.REQUEST_OPEN_MESSAGE
import eu.siacs.conversations.ui.ConversationsActivity.Companion.REQUEST_PLAY_PAUSE
import eu.siacs.conversations.ui.UriHandlerActivity
import javax.inject.Inject

@ActivityScope
class HandlePermissionsResultCommand @Inject constructor(
    private val activity: ConversationsActivity
) : (Int, Array<String>, IntArray) -> Unit {

    override fun invoke(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        UriHandlerActivity.onRequestPermissionResult(activity, requestCode, grantResults)
        if (grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                when (requestCode) {
                    REQUEST_OPEN_MESSAGE -> {
                        activity.refreshUiReal()
                        ConversationFragment.openPendingMessage(activity)
                    }
                    REQUEST_PLAY_PAUSE -> ConversationFragment.startStopPending(activity)
                }
            }
        }
    }
}