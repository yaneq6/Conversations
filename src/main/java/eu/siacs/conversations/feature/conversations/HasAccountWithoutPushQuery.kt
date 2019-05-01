package eu.siacs.conversations.feature.conversations

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.ui.ConversationsActivity
import javax.inject.Inject

@ActivityScope
class HasAccountWithoutPushQuery @Inject constructor(
    private val activity: ConversationsActivity
) : () -> Boolean {

    private val hasAccountWithPush: Account.() -> Boolean = {
        status == Account.State.ONLINE && activity.xmppConnectionService
            ?.pushManagementService
            ?.available(this)
            ?.not() ?: false
    }

    override fun invoke() = activity.xmppConnectionService.accounts.any(hasAccountWithPush)
}