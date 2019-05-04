package eu.siacs.conversations.feature.conversation.command

import android.os.Bundle
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.feature.conversation.query.GetIndexOf
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.utils.QuickLoader
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class ReInit @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val binding: FragmentConversationBinding,
    private val saveMessageDraftStopAudioPlayer: SaveMessageDraftStopAudioPlayer,
    private val clearPending: ClearPending,
    private val processExtras: ProcessExtras,
    private val resetUnreadMessagesCount: ResetUnreadMessagesCount,
    private val stopScrolling: StopScrolling,
    private val setupIme: SetupIme,
    private val refresh: Refresh,
    private val getIndexOf: GetIndexOf,
    private val setSelection: SetSelection,
    private val fireReadEvent: FireReadEvent
) {

    operator fun invoke(conversation: Conversation) {
        invoke(conversation, false)
    }

    operator fun invoke(conversation: Conversation, extras: Bundle?) {
        QuickLoader.set(conversation.uuid)
        this.saveMessageDraftStopAudioPlayer()
        this.clearPending()
        if (invoke(conversation, extras != null)) {
            if (extras != null) {
                processExtras(extras)
            }
            fragment.reInitRequiredOnStart = false
        } else {
            fragment.reInitRequiredOnStart = true
            fragment.pendingExtras.push(extras)
        }
        resetUnreadMessagesCount()
    }

    operator fun invoke(conversation: Conversation?, hasExtras: Boolean): Boolean {
        conversation ?: return false

        fragment.conversation = conversation
        //once we set the conversation all is good and it will automatically do the right thing in onStart()

        if (!activity.xmppConnectionService.isConversationStillOpen(conversation)) {
            activity.onConversationArchived(conversation)
            return false
        }

        stopScrolling()
        Timber.d("reInit(hasExtras=$hasExtras")

        if (conversation.isRead && hasExtras) {
            Timber.d("trimming conversation")
            conversation.trim()
        }

        setupIme()

        val scrolledToBottomAndNoPending = fragment.scrolledToBottom() && fragment.pendingScrollState.peek() == null

        binding.textSendButton.contentDescription = activity.getString(R.string.send_message_to_x, conversation.name)
        binding.textinput.setKeyboardListener(null)
        binding.textinput.setText("")
        val participating =
            conversation.mode == Conversational.MODE_SINGLE || conversation.mucOptions.participating()
        if (participating) {
            binding.textinput.append(conversation.nextMessage)
        }
        binding.textinput.setKeyboardListener(fragment)
        fragment.messageListAdapter.updatePreferences()
        refresh(false)
        conversation.messagesLoaded.set(true)
        Timber.d("scrolledToBottomAndNoPending=$scrolledToBottomAndNoPending")

        if (hasExtras || scrolledToBottomAndNoPending) {
            resetUnreadMessagesCount()
            synchronized(fragment.messageList) {
                Timber.d( "jump to first unread message")
                val first = conversation.firstUnreadMessage
                val bottom = Math.max(0, fragment.messageList.size - 1)
                val pos: Int
                val jumpToBottom: Boolean
                if (first == null) {
                    pos = bottom
                    jumpToBottom = true
                } else {
                    val i = getIndexOf(first.uuid, fragment.messageList)
                    pos = if (i < 0) bottom else i
                    jumpToBottom = false
                }
                setSelection(pos, jumpToBottom)
            }
        }


        binding.messagesView.post { fireReadEvent() }
        //TODO if we only do this when this fragment is running on main it won't *bing* in tablet layout which might be unnecessary since we can *see* it
        activity.xmppConnectionService.notificationService.setOpenConversation(conversation)
        return true
    }
}