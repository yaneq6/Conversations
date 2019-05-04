package eu.siacs.conversations.feature.conversation.command

import android.view.Menu
import android.view.MenuInflater
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.util.ConversationMenuConfigurator
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnCreateOptionsMenu @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) {

    operator fun invoke(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_conversation, menu)
        val conversation = fragment.conversation

        if (conversation != null) {
            val menuMucDetails = menu.findItem(R.id.action_muc_details)
            val menuContactDetails = menu.findItem(R.id.action_contact_details)
            val menuInviteContact = menu.findItem(R.id.action_invite)

            if (conversation.mode == Conversation.MODE_MULTI) {
                menuContactDetails.isVisible = false
                menuInviteContact.isVisible = conversation.mucOptions.canInvite()
                menuMucDetails.setTitle(if (conversation.mucOptions.isPrivateAndNonAnonymous) R.string.action_muc_details else R.string.channel_details)
            } else {
                menuContactDetails.isVisible = !conversation.withSelf()
                menuMucDetails.isVisible = false
                val service = activity.xmppConnectionService
                menuInviteContact.isVisible =
                    service?.findConferenceServer(conversation.account) != null
            }

            val menuMute = menu.findItem(R.id.action_mute)
            val menuUnmute = menu.findItem(R.id.action_unmute)
            if (conversation.isMuted) {
                menuMute.isVisible = false
            } else {
                menuUnmute.isVisible = false
            }
            ConversationMenuConfigurator.configureAttachmentMenu(
                conversation,
                menu
            )
            ConversationMenuConfigurator.configureEncryptionMenu(
                conversation,
                menu
            )
        }
    }
}