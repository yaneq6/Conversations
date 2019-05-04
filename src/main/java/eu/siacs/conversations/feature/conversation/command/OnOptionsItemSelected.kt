package eu.siacs.conversations.feature.conversation.command

import android.content.Intent
import android.view.MenuItem
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.*
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnOptionsItemSelected @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val handleEncryptionSelection: HandleEncryptionSelection,
    private val handleAttachmentSelection: HandleAttachmentSelection,
    private val clearHistoryDialog: ClearHistoryDialog,
    private val muteConversationDialog: MuteConversationDialog,
    private val unmuteConversation: UnmuteConversation
) {
    operator fun invoke(item: MenuItem): Boolean = fragment.conversation
        ?.takeUnless { MenuDoubleTabUtil.shouldIgnoreTap() }
        ?.let { conversation -> handle(conversation, item) } != null

    private fun handle(
        conversation: Conversation,
        item: MenuItem
    ): Any? = when (item.itemId) {
        R.id.encryption_choice_axolotl,
        R.id.encryption_choice_pgp,
        R.id.encryption_choice_none -> handleEncryptionSelection(item)

        R.id.attach_choose_picture,
        R.id.attach_take_picture,
        R.id.attach_record_video,
        R.id.attach_choose_file,
        R.id.attach_record_voice,
        R.id.attach_location -> handleAttachmentSelection(item)

        R.id.action_archive -> activity.xmppConnectionService.archiveConversation(
            conversation
        )

        R.id.action_contact_details -> activity.switchToContactDetails(conversation.contact)

        R.id.action_muc_details -> {
            val intent = Intent(
                activity,
                ConferenceDetailsActivity::class.java
            )
            intent.action = ConferenceDetailsActivity.ACTION_VIEW_MUC
            intent.putExtra("uuid", conversation.uuid)
            fragment.startActivity(intent)
        }

        R.id.action_invite -> fragment.startActivityForResult(
            ChooseContactActivity.create(activity, conversation),
            XmppActivity.REQUEST_INVITE_TO_CONVERSATION
        )

        R.id.action_clear_history -> clearHistoryDialog(conversation)

        R.id.action_mute -> muteConversationDialog(conversation)

        R.id.action_unmute -> unmuteConversation(conversation)

        R.id.action_block,
        R.id.action_unblock -> BlockContactDialog.show(
            activity,
            conversation
        )

        else -> null
    }
}