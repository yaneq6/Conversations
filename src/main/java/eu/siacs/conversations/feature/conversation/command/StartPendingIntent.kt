package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import android.app.PendingIntent
import android.content.IntentSender
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class StartPendingIntent @Inject constructor(
    private val activity: Activity
) : (PendingIntent, Int) -> Unit {
    override fun invoke(pendingIntent: PendingIntent, requestCode: Int) {
        try {
            activity.startIntentSenderForResult(
                pendingIntent.intentSender,
                requestCode,
                null,
                0,
                0,
                0
            )
        } catch (ignored: IntentSender.SendIntentException) {
        }
    }
}