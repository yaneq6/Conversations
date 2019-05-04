package eu.siacs.conversations.feature.conversation.callback

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.support.v13.view.inputmethod.InputConnectionCompat
import android.support.v13.view.inputmethod.InputContentInfoCompat
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.View.OnClickListener
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.TextView
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.conversation.REQUEST_ADD_EDITOR_CONTENT
import eu.siacs.conversations.feature.conversation.REQUEST_DECRYPT_PGP
import eu.siacs.conversations.feature.conversation.command.*
import eu.siacs.conversations.feature.conversation.query.GetIndexOf
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.BlockContactDialog
import eu.siacs.conversations.ui.ConferenceDetailsActivity
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.util.SendButtonAction
import eu.siacs.conversations.ui.widget.EditMessage
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject


@ActivityScope
class ClickToMuc @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : OnClickListener {
    override fun onClick(v: View?) {
        val intent = Intent(activity, ConferenceDetailsActivity::class.java)
        intent.action = ConferenceDetailsActivity.ACTION_VIEW_MUC
        intent.putExtra("uuid", fragment.conversation!!.uuid)
        fragment.startActivity(intent)
    }
}

@ActivityScope
class LeaveMuc @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : OnClickListener {
    override fun onClick(view: View) {
        activity.xmppConnectionService.archiveConversation(fragment.conversation)
    }
}

@ActivityScope
class JoinMuc @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : OnClickListener {
    override fun onClick(view: View) {
        activity.xmppConnectionService.joinMuc(fragment.conversation)
    }
}

@ActivityScope
class AcceptJoin @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : OnClickListener {
    override fun onClick(view: View) {
        val conversation = fragment.conversation!!
        conversation.setAttribute("accept_non_anonymous", true)
        activity.xmppConnectionService.updateConversation(conversation)
        activity.xmppConnectionService.joinMuc(conversation)
    }
}

@ActivityScope
class EnterPassword @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : OnClickListener {
    override fun onClick(view: View) {
        val conversation = fragment.conversation!!
        val muc = conversation.mucOptions
        var password: String? = muc.password
        if (password == null) {
            password = ""
        }
        activity.quickPasswordEdit(password) { value ->
            activity.xmppConnectionService.providePasswordForMuc(conversation, value)
            null
        }
    }
}

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
                    object : XmppConnectionService.OnMoreMessagesLoaded {
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
                                        Toast.makeText(view.context, resId, Toast.LENGTH_LONG)
                                            .apply { show() }
                                }
                            }
                        }
                    })

            }
        }
    }
}

@ActivityScope
class EditorContentListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val hasPermissions: HasPermissions,
    private val attachEditorContentToConversation: AttachEditorContentToConversation
) : EditMessage.OnCommitContentListener {

    override fun onCommitContent(
        inputContentInfo: InputContentInfoCompat,
        flags: Int,
        opts: Bundle?,
        mimeTypes: Array<out String>?
    ): Boolean {
        // try to get permission to read the image, if applicable
        if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
            try {
                inputContentInfo.requestPermission()
            } catch (e: Exception) {
                Timber.e(e, "InputContentInfoCompat#requestPermission() failed.")
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.no_permission_to_access_x,
                        inputContentInfo.description
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return false
            }

        }
        if (hasPermissions(
                REQUEST_ADD_EDITOR_CONTENT,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            attachEditorContentToConversation(inputContentInfo.contentUri)
        } else {
            fragment.pendingEditorContent = inputContentInfo.contentUri
        }
        return true
    }
}

@ActivityScope
class EnableAccountListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : OnClickListener {
    override fun onClick(view: View) {
        val account = fragment.conversation?.account
        if (account != null) {
            account.setOption(Account.OPTION_DISABLED, false)
            activity.xmppConnectionService.updateAccount(account)
        }
    }
}

@ActivityScope
class UnblockClickListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val unblockConversation: UnblockConversation
) : OnClickListener {
    override fun onClick(view: View) {
        view.post { view.visibility = View.INVISIBLE }
        val conversation = fragment.conversation!!
        if (conversation.isDomainBlocked) {
            BlockContactDialog.show(activity, conversation)
        } else {
            unblockConversation(conversation)
        }
    }
}

@ActivityScope
class BlockClickListener @Inject constructor(
    private val showBlockSubmenu: ShowBlockSubmenu
) : OnClickListener {
    override fun onClick(view: View) {
        showBlockSubmenu(view)
    }
}

@ActivityScope
class AddBackClickListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : OnClickListener {
    override fun onClick(view: View) {
        val conversation = fragment.conversation
        val contact = if (conversation == null) null else conversation.contact
        if (contact != null) {
            activity.xmppConnectionService.createContact(contact, true)
            activity.switchToContactDetails(contact)
        }
    }
}

@ActivityScope
class LongPressBlockListener @Inject constructor(
    private val showBlockSubmenu: ShowBlockSubmenu
) : View.OnLongClickListener {
    override fun onLongClick(view: View): Boolean {
        return showBlockSubmenu(view)
    }
}

@ActivityScope
class AllowPresenceSubscription @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val hideSnackbar: HideSnackbar
) : OnClickListener {
    override fun onClick(view: View) {
        fragment.conversation?.contact?.let { contact ->
            activity.xmppConnectionService.sendPresencePacket(
                contact.account,
                activity.xmppConnectionService.presenceGenerator
                    .sendPresenceUpdatesTo(contact)
            )
            hideSnackbar()
        }
    }
}

@ActivityScope
class ClickToDecryptListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity,
    private val updateSnackBar: UpdateSnackBar
) : OnClickListener {
    override fun onClick(view: View) {
        val conversation = fragment.conversation!!
        val pendingIntent = conversation.account.pgpDecryptionService.pendingIntent
        if (pendingIntent != null) {
            try {
                activity.startIntentSenderForResult(
                    pendingIntent.intentSender,
                    REQUEST_DECRYPT_PGP, null,
                    0,
                    0,
                    0
                )
            } catch (e: IntentSender.SendIntentException) {
                Toast.makeText(
                    activity,
                    R.string.unable_to_connect_to_keychain,
                    Toast.LENGTH_SHORT
                ).show()
                conversation.account.pgpDecryptionService.continueDecryption(true)
            }
        }
        updateSnackBar(conversation)
    }
}

@ActivityScope
class EditorActionListener @Inject constructor(
    private val activity: ConversationsActivity,
    private val sendMessage: SendMessage
) : TextView.OnEditorActionListener {
    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean =
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            val imm =
                activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (imm.isFullscreenMode) {
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
            sendMessage()
            true
        } else
            false
}

@ActivityScope
class ScrollButtonListener @Inject constructor(
    private val binding: FragmentConversationBinding,
    private val stopScrolling: StopScrolling,
    private val setSelection: SetSelection
) : OnClickListener {
    override fun onClick(view: View) {
        stopScrolling()
        setSelection(binding.messagesView.count - 1, true)
    }
}

@ActivityScope
class SendButtonListener @Inject constructor(
    private val fragment: ConversationFragment,
    private val binding: FragmentConversationBinding,
    private val updateChatMsgHint: UpdateChatMsgHint,
    private val updateSendButton: UpdateSendButton,
    private val updateEditablity: UpdateEditablity,
    private val sendMessage: SendMessage,
    private val attachFile: AttachFile
) : OnClickListener {
    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is SendButtonAction) when (tag) {

            SendButtonAction.TAKE_PHOTO,
            SendButtonAction.RECORD_VIDEO,
            SendButtonAction.SEND_LOCATION,
            SendButtonAction.RECORD_VOICE,
            SendButtonAction.CHOOSE_PICTURE -> attachFile(
                tag.toChoice()
            )

            SendButtonAction.CANCEL -> fragment.conversation?.let { conversation ->
                if (conversation.setCorrectingMessage(null)) {
                    binding.textinput.setText("")
                    binding.textinput.append(conversation.draftMessage)
                    conversation.draftMessage = null
                } else if (conversation.mode == Conversation.MODE_MULTI) {
                    conversation.nextCounterpart = null
                }
                updateChatMsgHint()
                updateSendButton()
                updateEditablity()
            }

            else -> sendMessage()
        } else sendMessage()
    }
}