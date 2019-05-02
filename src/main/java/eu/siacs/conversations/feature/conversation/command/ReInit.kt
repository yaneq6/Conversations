package eu.siacs.conversations.feature.conversation.command

import android.os.Bundle
import android.util.Log
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.utils.QuickLoader
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ReInit @Inject constructor(
    private val fragment: ConversationFragment
) {

    operator fun invoke(conversation: Conversation) = fragment.run {
        reInit(conversation, false)
    }

    operator fun invoke(conversation: Conversation, extras: Bundle?) = fragment.run {
        QuickLoader.set(conversation.uuid)
        this.saveMessageDraftStopAudioPlayer()
        this.clearPending()
        if (this.reInit(conversation, extras != null)) {
            if (extras != null) {
                processExtras(extras)
            }
            this.reInitRequiredOnStart = false
        } else {
            this.reInitRequiredOnStart = true
            pendingExtras.push(extras)
        }
        resetUnreadMessagesCount()
    }

    operator fun invoke(conversation: Conversation?, hasExtras: Boolean): Boolean = fragment.run {
        if (conversation == null) {
            return false
        }
        this.conversation = conversation
        //once we set the conversation all is good and it will automatically do the right thing in onStart()
        if (this.activity == null || this.binding == null) {
            return false
        }

        if (!activity!!.xmppConnectionService.isConversationStillOpen(this.conversation)) {
            activity!!.onConversationArchived(this.conversation!!)
            return false
        }

        stopScrolling()
        Log.d(
            Config.LOGTAG,
            "reInit(hasExtras=" + java.lang.Boolean.toString(hasExtras) + ")"
        )

        if (this.conversation!!.isRead && hasExtras) {
            Log.d(Config.LOGTAG, "trimming conversation")
            this.conversation!!.trim()
        }

        setupIme()

        val scrolledToBottomAndNoPending = this.scrolledToBottom() && pendingScrollState.peek() == null

        this.binding!!.textSendButton.contentDescription =
            activity!!.getString(R.string.send_message_to_x, conversation.name)
        this.binding!!.textinput.setKeyboardListener(null)
        this.binding!!.textinput.setText("")
        val participating =
            conversation.mode == Conversational.MODE_SINGLE || conversation.mucOptions.participating()
        if (participating) {
            this.binding!!.textinput.append(this.conversation!!.nextMessage)
        }
        this.binding!!.textinput.setKeyboardListener(this)
        messageListAdapter.updatePreferences()
        refresh(false)
        this.conversation!!.messagesLoaded.set(true)
        Log.d(
            Config.LOGTAG,
            "scrolledToBottomAndNoPending=" + java.lang.Boolean.toString(scrolledToBottomAndNoPending)
        )

        if (hasExtras || scrolledToBottomAndNoPending) {
            resetUnreadMessagesCount()
            synchronized(this.messageList) {
                Log.d(Config.LOGTAG, "jump to first unread message")
                val first = conversation.firstUnreadMessage
                val bottom = Math.max(0, this.messageList.size - 1)
                val pos: Int
                val jumpToBottom: Boolean
                if (first == null) {
                    pos = bottom
                    jumpToBottom = true
                } else {
                    val i = getIndexOf(first.uuid, this.messageList)
                    pos = if (i < 0) bottom else i
                    jumpToBottom = false
                }
                setSelection(pos, jumpToBottom)
            }
        }


        this.binding!!.messagesView.post { this.fireReadEvent() }
        //TODO if we only do this when this fragment is running on main it won't *bing* in tablet layout which might be unnecessary since we can *see* it
        activity!!.xmppConnectionService.notificationService.setOpenConversation(this.conversation)
        return true
    }
}