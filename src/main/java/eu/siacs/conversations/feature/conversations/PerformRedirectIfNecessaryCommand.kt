package eu.siacs.conversations.feature.conversations

import android.content.Intent
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.utils.SignupUtils
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@ActivityScope
class PerformRedirectIfNecessaryCommand @Inject constructor(
    private val activity: ConversationsActivity,
    private val redirectInProcess: AtomicBoolean
) :
        (Boolean, Conversation?) -> Boolean,
        (Boolean) -> Boolean {

    override fun invoke(noAnimation: Boolean) = invoke(noAnimation, null)

    override fun invoke(noAnimation: Boolean, ignore: Conversation?): Boolean = activity.run {
        if (xmppConnectionService == null) {
            return false
        }
        val isConversationsListEmpty = xmppConnectionService.isConversationsListEmpty(ignore)
        if (isConversationsListEmpty && redirectInProcess.compareAndSet(false, true)) {
            val intent = SignupUtils.getRedirectionIntent(this)
            if (noAnimation) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            runOnUiThread {
                startActivity(intent)
                if (noAnimation) {
                    overridePendingTransition(0, 0)
                }
            }
        }
        return redirectInProcess.get()
    }
}