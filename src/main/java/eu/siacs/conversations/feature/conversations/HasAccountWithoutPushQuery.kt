package eu.siacs.conversations.feature.conversations

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.services.XmppConnectionService

class HasAccountWithoutPushQuery(private val service: XmppConnectionService) : () -> Boolean {

    override fun invoke(): Boolean = service.accounts.any(hasAccountWithPush)

    private val hasAccountWithPush: Account.() -> Boolean = {
        status == Account.State.ONLINE && !service.pushManagementService.available(this)
    }
}