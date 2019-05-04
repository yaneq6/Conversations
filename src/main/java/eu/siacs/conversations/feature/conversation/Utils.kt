package eu.siacs.conversations.feature.conversation

import android.app.Activity
import android.app.Fragment
import android.support.annotation.IdRes
import android.widget.AbsListView
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ConversationFragment

fun Activity.hidePrepareFileToast(prepareFileToast: Toast?) {
    prepareFileToast?.run { runOnUiThread { cancel() } }
}


const val REQUEST_SEND_MESSAGE = 0x0201
const val REQUEST_DECRYPT_PGP = 0x0202
const val REQUEST_ENCRYPT_MESSAGE = 0x0207
const val REQUEST_TRUST_KEYS_TEXT = 0x0208
const val REQUEST_TRUST_KEYS_ATTACHMENTS = 0x0209
const val REQUEST_START_DOWNLOAD = 0x0210
const val REQUEST_ADD_EDITOR_CONTENT = 0x0211
const val REQUEST_COMMIT_ATTACHMENTS = 0x0212
const val ATTACHMENT_CHOICE_CHOOSE_IMAGE = 0x0301
const val ATTACHMENT_CHOICE_TAKE_PHOTO = 0x0302
const val ATTACHMENT_CHOICE_CHOOSE_FILE = 0x0303
const val ATTACHMENT_CHOICE_RECORD_VOICE = 0x0304
const val ATTACHMENT_CHOICE_LOCATION = 0x0305
const val ATTACHMENT_CHOICE_INVALID = 0x0306
const val ATTACHMENT_CHOICE_RECORD_VIDEO = 0x0307

const val RECENTLY_USED_QUICK_ACTION = "recently_used_quick_action"
val STATE_CONVERSATION_UUID = ConversationFragment::class.java.name + ".uuid"
val STATE_SCROLL_POSITION = ConversationFragment::class.java.name + ".scroll_position"
val STATE_PHOTO_URI = ConversationFragment::class.java.name + ".media_previews"
val STATE_MEDIA_PREVIEWS = ConversationFragment::class.java.name + ".take_photo_uri"
const val STATE_LAST_MESSAGE_UUID = "state_last_message_uuid"

fun findConversationFragment(activity: Activity): ConversationFragment? {
    var fragment: Fragment? = activity.fragmentManager.findFragmentById(R.id.main_fragment)
    if (fragment != null && fragment is ConversationFragment) {
        return fragment
    }
    fragment = activity.fragmentManager.findFragmentById(R.id.secondary_fragment)
    return if (fragment != null && fragment is ConversationFragment) {
        fragment
    } else null
}

fun startStopPending(activity: Activity) {
    val fragment = findConversationFragment(activity)
    fragment?.messageListAdapter?.startStopPending()
}

fun downloadFile(activity: Activity, message: Message) {
    val fragment = findConversationFragment(activity)
    fragment?.startDownloadable?.invoke(message)
}

fun registerPendingMessage(activity: Activity, message: Message) {
    val fragment = findConversationFragment(activity)
    fragment?.pendingMessage?.push(message)
}

fun openPendingMessage(activity: Activity) {
    val fragment = findConversationFragment(activity)
    if (fragment != null) {
        val message = fragment.pendingMessage.pop()
        if (message != null) {
            fragment.messageListAdapter.openDownloadable(message)
        }
    }
}

fun getConversation(activity: Activity): Conversation? {
    return getConversation(activity, R.id.secondary_fragment)
}

fun getConversation(activity: Activity, @IdRes res: Int): Conversation? {
    val fragment = activity.fragmentManager.findFragmentById(res)
    return if (fragment != null && fragment is ConversationFragment) {
        fragment.conversation
    } else {
        null
    }
}

fun get(activity: Activity): ConversationFragment? {
    val fragmentManager = activity.fragmentManager
    var fragment: Fragment? = fragmentManager.findFragmentById(R.id.main_fragment)
    return if (fragment != null && fragment is ConversationFragment) {
        fragment
    } else {
        fragment = fragmentManager.findFragmentById(R.id.secondary_fragment)
        if (fragment != null && fragment is ConversationFragment) fragment else null
    }
}

fun getConversationReliable(activity: Activity): Conversation? {
    val conversation = getConversation(activity, R.id.secondary_fragment)
    return conversation ?: getConversation(activity, R.id.main_fragment)
}

fun scrolledToBottom(listView: AbsListView): Boolean {
    val count = listView.count
    if (count == 0) {
        return true
    } else if (listView.lastVisiblePosition == count - 1) {
        val lastChild = listView.getChildAt(listView.childCount - 1)
        return lastChild != null && lastChild.bottom <= listView.height
    } else {
        return false
    }
}