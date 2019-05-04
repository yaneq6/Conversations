package eu.siacs.conversations.feature.conversation.callback

import android.util.Log
import android.widget.AbsListView
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.conversation.command.FireReadEvent
import eu.siacs.conversations.feature.conversation.command.ToggleScrollDownButton
import eu.siacs.conversations.feature.conversation.command.UpdateStatusMessages
import eu.siacs.conversations.feature.conversation.query.GetIndexOf
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnScrollListener @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val fireReadEvent: FireReadEvent,
    private val toggleScrollDownButton: ToggleScrollDownButton,
    private val updateStatusMessages: UpdateStatusMessages,
    private val getIndexOf: GetIndexOf
) : AbsListView.OnScrollListener {

    override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
        if (AbsListView.OnScrollListener.SCROLL_STATE_IDLE == scrollState) {
            fireReadEvent()
        }
    }

    override fun onScroll(
        view: AbsListView,
        firstVisibleItem: Int,
        visibleItemCount: Int,
        totalItemCount: Int
    ) {
        toggleScrollDownButton(view)
        synchronized(fragment.messageList) {
            val conversation = fragment.conversation
            val messageList = fragment.messageList
            if (firstVisibleItem < 5 && conversation != null && conversation.messagesLoaded.compareAndSet(
                    true,
                    false
                ) && messageList.size > 0
            ) {
                val timestamp: Long
                if (messageList[0].type == Message.TYPE_STATUS && messageList.size >= 2) {
                    timestamp = messageList[1].timeSent
                } else {
                    timestamp = messageList[0].timeSent
                }
                activity.xmppConnectionService.loadMoreMessages(
                    conversation,
                    timestamp,
                    object :
                        XmppConnectionService.OnMoreMessagesLoaded {
                        override fun onMoreMessagesLoaded(c: Int, conversation: Conversation) {
                            if (fragment.conversation !== conversation) {
                                conversation.messagesLoaded.set(true)
                                return
                            }
                            fragment.runOnUiThread {
                                synchronized(messageList) {
                                    val oldPosition =
                                        binding.messagesView.firstVisiblePosition
                                    var message: Message? = null
                                    var childPos: Int = 0
                                    while (childPos + oldPosition < messageList.size) {
                                        message = messageList[oldPosition + childPos]
                                        if (message.type != Message.TYPE_STATUS) {
                                            break
                                        }
                                        ++childPos
                                    }
                                    val uuid = message?.uuid
                                    val v = binding.messagesView.getChildAt(childPos)
                                    val pxOffset = v?.top ?: 0
                                    fragment.conversation!!.populateWithMessages(
                                        fragment.messageList
                                    )
                                    try {
                                        updateStatusMessages()
                                    } catch (e: IllegalStateException) {
                                        Log.d(
                                            Config.LOGTAG,
                                            "caught illegal state exception while updating status messages"
                                        )
                                    }

                                    fragment.messageListAdapter.notifyDataSetChanged()
                                    val pos = Math.max(getIndexOf(uuid, messageList), 0)
                                    binding.messagesView.setSelectionFromTop(pos, pxOffset)
                                    if (fragment.messageLoaderToast != null) {
                                        fragment.messageLoaderToast!!.cancel()
                                    }
                                    conversation.messagesLoaded.set(true)
                                }
                            }
                        }

                        override fun informUser(resId: Int) {
                            fragment.runOnUiThread {
                                fragment.messageLoaderToast?.cancel()
                                if (fragment.conversation === conversation) {
                                    fragment.messageLoaderToast =
                                        Toast.makeText(
                                            view.context,
                                            resId,
                                            Toast.LENGTH_LONG
                                        )
                                            .apply { show() }
                                }
                            }
                        }
                    })

            }
        }
    }
}