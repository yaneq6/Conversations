package eu.siacs.conversations.feature.conversation.command

import android.view.MenuItem
import eu.siacs.conversations.R
import eu.siacs.conversations.feature.conversation.*
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class HandleAttachmentSelection @Inject constructor(
    private val fragment: ConversationFragment
) : (MenuItem) -> Unit {
    override fun invoke(item: MenuItem) {
        when (item.itemId) {
            R.id.attach_choose_picture -> ATTACHMENT_CHOICE_CHOOSE_IMAGE
            R.id.attach_take_picture -> ATTACHMENT_CHOICE_TAKE_PHOTO
            R.id.attach_record_video -> ATTACHMENT_CHOICE_RECORD_VIDEO
            R.id.attach_choose_file -> ATTACHMENT_CHOICE_CHOOSE_FILE
            R.id.attach_record_voice -> ATTACHMENT_CHOICE_RECORD_VOICE
            R.id.attach_location -> ATTACHMENT_CHOICE_LOCATION
            else -> null
        }?.let(fragment.attachFile)
    }
}