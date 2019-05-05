package eu.siacs.conversations.feature.xmpp

import android.content.Intent
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ChooseContactActivity
import eu.siacs.conversations.ui.XmppActivity
import rocks.xmpp.addr.Jid
import java.util.*

class ConferenceInvite {
    var uuid: String? = null
    val jids = ArrayList<Jid>()

    fun execute(activity: XmppActivity): Boolean {
        val service = activity.xmppConnectionService
        val conversation = service!!.findConversationByUuid(this.uuid) ?: return false
        if (conversation.mode == Conversation.MODE_MULTI) {
            for (jid in jids) {
                service.invite(conversation, jid)
            }
            return false
        } else {
            jids.add(conversation.jid.asBareJid())
            return service.createAdhocConference(
                conversation.account,
                null,
                jids,
                activity.adhocCallback
            )
        }
    }

    companion object {

        fun parse(data: Intent): ConferenceInvite? {
            val invite = ConferenceInvite()
            invite.uuid = data.getStringExtra(ChooseContactActivity.EXTRA_CONVERSATION)
            if (invite.uuid == null) {
                return null
            }
            invite.jids.addAll(ChooseContactActivity.extractJabberIds(data))
            return invite
        }
    }
}