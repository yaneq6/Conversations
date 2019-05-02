package eu.siacs.conversations.feature.conversation.command

import android.app.PendingIntent
import android.content.IntentSender
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class StartPendingIntent @Inject constructor(
    private val fragment: ConversationFragment
) : (PendingIntent, Int) -> Unit {
    override fun invoke(pendingIntent: PendingIntent, requestCode: Int) = fragment.run {
        try {
            getActivity().startIntentSenderForResult(pendingIntent.intentSender, requestCode, null, 0, 0, 0)
        } catch (ignored: IntentSender.SendIntentException) {
        }

    }
}