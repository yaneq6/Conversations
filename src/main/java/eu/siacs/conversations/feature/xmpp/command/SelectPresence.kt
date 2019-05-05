package eu.siacs.conversations.feature.xmpp.command

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.PresenceSelector
import io.aakit.scope.ActivityScope
import rocks.xmpp.addr.Jid
import javax.inject.Inject

@ActivityScope
class SelectPresence @Inject constructor(
    private val activity: XmppActivity,
    private val showAddToRosterDialog: ShowAddToRosterDialog,
    private val showAskForPresenceDialog: ShowAskForPresenceDialog
) {
    operator fun invoke(conversation: Conversation, listener: PresenceSelector.OnPresenceSelected) {
        val contact = conversation.contact
        if (!contact.showInRoster()) {
            showAddToRosterDialog(conversation.contact)
        } else {
            val presences = contact.presences
            if (presences.size() == 0) {
                if (!contact.getOption(Contact.Options.TO)
                    && !contact.getOption(Contact.Options.ASKING)
                    && contact.account.status == Account.State.ONLINE
                ) {
                    showAskForPresenceDialog(contact)
                } else if (!contact.getOption(Contact.Options.TO) || !contact.getOption(
                        Contact.Options.FROM
                    )) {
                    PresenceSelector.warnMutualPresenceSubscription(
                        activity,
                        conversation,
                        listener
                    )
                } else {
                    conversation.nextCounterpart = null
                    listener.onPresenceSelected()
                }
            } else if (presences.size() == 1) {
                val presence = presences.toResourceArray()[0]
                try {
                    conversation.nextCounterpart =
                        Jid.of(contact.jid.local, contact.jid.domain, presence)
                } catch (e: IllegalArgumentException) {
                    conversation.nextCounterpart = null
                }

                listener.onPresenceSelected()
            } else {
                PresenceSelector.showPresenceSelectionDialog(
                    activity,
                    conversation,
                    listener
                )
            }
        }
    }
}