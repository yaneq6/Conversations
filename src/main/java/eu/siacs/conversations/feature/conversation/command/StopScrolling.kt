package eu.siacs.conversations.feature.conversation.command

import android.os.SystemClock
import android.view.MotionEvent
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class StopScrolling @Inject constructor(
    private val fragment: ConversationFragment
) : () -> Unit {
    override fun invoke() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        fragment.binding!!.messagesView.dispatchTouchEvent(cancel)
    }
}