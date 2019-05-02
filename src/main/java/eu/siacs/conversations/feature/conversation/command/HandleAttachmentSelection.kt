package eu.siacs.conversations.feature.conversation.command

import android.view.MenuItem
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HandleAttachmentSelection @Inject constructor(
    private val fragment: ConversationFragment
) : (MenuItem) -> Unit {
    override fun invoke(item: MenuItem) {
        when (item.itemId) {
            R.id.attach_choose_picture -> ConversationFragment.ATTACHMENT_CHOICE_CHOOSE_IMAGE
            R.id.attach_take_picture -> ConversationFragment.ATTACHMENT_CHOICE_TAKE_PHOTO
            R.id.attach_record_video -> ConversationFragment.ATTACHMENT_CHOICE_RECORD_VIDEO
            R.id.attach_choose_file -> ConversationFragment.ATTACHMENT_CHOICE_CHOOSE_FILE
            R.id.attach_record_voice -> ConversationFragment.ATTACHMENT_CHOICE_RECORD_VOICE
            R.id.attach_location -> ConversationFragment.ATTACHMENT_CHOICE_LOCATION
            else -> null
        }?.let(fragment.attachFile)
    }
}