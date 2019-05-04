package eu.siacs.conversations.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender.SendIntentException
import android.net.Uri
import android.os.Bundle
import android.support.v13.view.inputmethod.InputConnectionCompat
import android.util.Log
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.view.View.OnClickListener
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.AbsListView.OnScrollListener
import android.widget.TextView.OnEditorActionListener
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
import eu.siacs.conversations.ui.adapter.MediaPreviewAdapter
import eu.siacs.conversations.ui.adapter.MessageAdapter
import eu.siacs.conversations.ui.util.*
import eu.siacs.conversations.ui.widget.EditMessage
import timber.log.Timber
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject


class ConversationFragment : XmppFragment(),
    EditMessage.KeyboardListener,
    MessageAdapter.OnContactPictureLongClicked,
    MessageAdapter.OnContactPictureClicked {

    @Inject
    lateinit var getIndexOf: GetIndexOf
    @Inject
    lateinit var toggleScrollDownButton: ToggleScrollDownButton
    @Inject
    lateinit var sendMessage: SendMessage
    @Inject
    lateinit var handleActivityResult: HandleActivityResult
    @Inject
    lateinit var toggleInputMethod: ToggleInputMethod
    @Inject
    lateinit var attachEditorContentToConversation: AttachEditorContentToConversation
    @Inject
    lateinit var attachFile: AttachFile
    @Inject
    lateinit var findAndReInitByUuidOrArchive: FindAndReInitByUuidOrArchive
    @Inject
    lateinit var fireReadEvent: FireReadEvent
    @Inject
    lateinit var hasPermissions: HasPermissions
    @Inject
    lateinit var hideSnackbar: HideSnackbar
    @Inject
    lateinit var highlightInConference: HighlightInConference
    @Inject
    lateinit var processExtras: ProcessExtras
    @Inject
    lateinit var quoteText: QuoteText
    @Inject
    lateinit var refresh: Refresh
    @Inject
    lateinit var reInit: ReInit
    @Inject
    lateinit var scrolledToBottom: ScrolledToBottom
    @Inject
    lateinit var setSelection: SetSelection
    @Inject
    lateinit var showBlockSubmenu: ShowBlockSubmenu
    @Inject
    lateinit var startDownloadable: StartDownloadable
    @Inject
    lateinit var stopScrolling: StopScrolling
    @Inject
    lateinit var storeNextMessage: StoreNextMessage
    @Inject
    lateinit var unblockConversation: UnblockConversation
    @Inject
    lateinit var updateChatMsgHint: UpdateChatMsgHint
    @Inject
    lateinit var updateChatState: UpdateChatState
    @Inject
    lateinit var updateEditablity: UpdateEditablity
    @Inject
    lateinit var updateSendButton: UpdateSendButton
    @Inject
    lateinit var updateSnackBar: UpdateSnackBar
    @Inject
    lateinit var updateStatusMessages: UpdateStatusMessages
    @Inject
    lateinit var onCreateOptionsMenu: OnCreateOptionsMenu
    //    @Inject
//    lateinit var onCreateView: OnCreateView
    @Inject
    lateinit var onCreateContextMenu: OnCreateContextMenu
    @Inject
    lateinit var onContextItemSelected: OnContextItemSelected
    @Inject
    lateinit var onOptionsItemSelected: OnOptionsItemSelected
    @Inject
    lateinit var onRequestPermissionsResult: OnRequestPermissionsResult
    @Inject
    lateinit var onResume: OnResume
    @Inject
    lateinit var onSaveInstanceState: OnSaveInstanceState
    @Inject
    lateinit var onActivityCreated: OnActivityCreated
    @Inject
    lateinit var onStart: OnStart
    @Inject
    lateinit var onStop: OnStop
    @Inject
    lateinit var onEnterPressed: OnEnterPressed
    @Inject
    lateinit var onTypingStarted: OnTypingStarted
    @Inject
    lateinit var onTypingStopped: OnTypingStopped
    @Inject
    lateinit var onTextDeleted: OnTextDeleted
    @Inject
    lateinit var onTextChanged: OnTextChanged
    @Inject
    lateinit var onTabPressed: OnTabPressed
    @Inject
    lateinit var onBackendConnected: OnBackendConnected
    @Inject
    lateinit var onContactPictureLongClicked: OnContactPictureLongClicked
    @Inject
    lateinit var onContactPictureClicked: OnContactPictureClicked
    @Inject
    lateinit var privateMessageWith: PrivateMessageWith

    private val onAttach = OnAttach()

    val messageList = ArrayList<Message>()
    val postponedActivityResult = PendingItem<ActivityResult>()
    val pendingConversationsUuid = PendingItem<String>()
    val pendingMediaPreviews = PendingItem<ArrayList<Attachment>>()
    val pendingExtras = PendingItem<Bundle>()
    val pendingTakePhotoUri = PendingItem<Uri>()
    val pendingScrollState = PendingItem<ScrollState>()
    val pendingLastMessageUuid = PendingItem<String>()
    val pendingMessage = PendingItem<Message>()
    val sendingPgpMessage = AtomicBoolean(false)
    lateinit var messageListAdapter: MessageAdapter
    var pendingEditorContent: Uri? = null
    var mediaPreviewAdapter: MediaPreviewAdapter? = null
    var lastMessageUuid: String? = null
    var conversation: Conversation? = null
    var binding: FragmentConversationBinding? = null
    var messageLoaderToast: Toast? = null
    var activity: ConversationsActivity? = null
    var reInitRequiredOnStart = true
    var selectedMessage: Message? = null
    var completionIndex = 0
    var lastCompletionLength = 0
    var incomplete: String? = null
    var lastCompletionCursor: Int = 0
    var firstWord = false
    var pendingDownloadableMessage: Message? = null

    val clickToMuc = OnClickListener {
        val intent = Intent(getActivity(), ConferenceDetailsActivity::class.java)
        intent.action = ConferenceDetailsActivity.ACTION_VIEW_MUC
        intent.putExtra("uuid", conversation!!.uuid)
        startActivity(intent)
    }

    val leaveMuc = OnClickListener {
        activity!!.xmppConnectionService.archiveConversation(conversation)
    }

    val joinMuc = OnClickListener {
        activity!!.xmppConnectionService.joinMuc(conversation)
    }

    val acceptJoin = OnClickListener {
        conversation!!.setAttribute("accept_non_anonymous", true)
        activity!!.xmppConnectionService.updateConversation(conversation)
        activity!!.xmppConnectionService.joinMuc(conversation)
    }

    val enterPassword = OnClickListener {
        val muc = conversation!!.mucOptions
        var password: String? = muc.password
        if (password == null) {
            password = ""
        }
        activity!!.quickPasswordEdit(password) { value ->
            activity!!.xmppConnectionService.providePasswordForMuc(conversation, value)
            null
        }
    }
    val onScrollListener = object : OnScrollListener {

        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
            if (OnScrollListener.SCROLL_STATE_IDLE == scrollState) {
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
            synchronized(this@ConversationFragment.messageList) {
                if (firstVisibleItem < 5 && conversation != null && conversation!!.messagesLoaded.compareAndSet(
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
                    activity!!.xmppConnectionService.loadMoreMessages(
                        conversation,
                        timestamp,
                        object : XmppConnectionService.OnMoreMessagesLoaded {
                            override fun onMoreMessagesLoaded(c: Int, conversation: Conversation) {
                                if (this@ConversationFragment.conversation !== conversation) {
                                    conversation.messagesLoaded.set(true)
                                    return
                                }
                                runOnUiThread {
                                    synchronized(messageList) {
                                        val oldPosition =
                                            binding!!.messagesView.firstVisiblePosition
                                        var message: Message? = null
                                        var childPos: Int
                                        childPos = 0
                                        while (childPos + oldPosition < messageList.size) {
                                            message = messageList[oldPosition + childPos]
                                            if (message.type != Message.TYPE_STATUS) {
                                                break
                                            }
                                            ++childPos
                                        }
                                        val uuid = message?.uuid
                                        val v = binding!!.messagesView.getChildAt(childPos)
                                        val pxOffset = v?.top ?: 0
                                        this@ConversationFragment.conversation!!.populateWithMessages(
                                            this@ConversationFragment.messageList
                                        )
                                        try {
                                            updateStatusMessages()
                                        } catch (e: IllegalStateException) {
                                            Log.d(
                                                Config.LOGTAG,
                                                "caught illegal state exception while updating status messages"
                                            )
                                        }

                                        messageListAdapter.notifyDataSetChanged()
                                        val pos = Math.max(getIndexOf(uuid, messageList), 0)
                                        binding!!.messagesView.setSelectionFromTop(pos, pxOffset)
                                        if (messageLoaderToast != null) {
                                            messageLoaderToast!!.cancel()
                                        }
                                        conversation.messagesLoaded.set(true)
                                    }
                                }
                            }

                            override fun informUser(resId: Int) {

                                runOnUiThread {
                                    if (messageLoaderToast != null) {
                                        messageLoaderToast!!.cancel()
                                    }
                                    if (this@ConversationFragment.conversation !== conversation) {
                                        return@runOnUiThread
                                    }
                                    messageLoaderToast =
                                        Toast.makeText(view.context, resId, Toast.LENGTH_LONG)
                                    messageLoaderToast!!.show()
                                }

                            }
                        })

                }
            }
        }
    }

    val editorContentListener = EditMessage.OnCommitContentListener { inputContentInfo, flags, opts, contentMimeTypes ->
            // try to get permission to read the image, if applicable
            if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                try {
                    inputContentInfo.requestPermission()
                } catch (e: Exception) {
                    Timber.e(e, "InputContentInfoCompat#requestPermission() failed.")
                    Toast.makeText(
                        getActivity(),
                        activity!!.getString(
                            R.string.no_permission_to_access_x,
                            inputContentInfo.description
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    return@OnCommitContentListener false
                }

            }
            if (hasPermissions(
                    REQUEST_ADD_EDITOR_CONTENT,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            ) {
                attachEditorContentToConversation(inputContentInfo.contentUri)
            } else {
                pendingEditorContent = inputContentInfo.contentUri
            }
            true
        }

    val enableAccountListener = OnClickListener {
        val account = if (conversation == null) null else conversation!!.account
        if (account != null) {
            account.setOption(Account.OPTION_DISABLED, false)
            activity!!.xmppConnectionService.updateAccount(account)
        }
    }

    val unblockClickListener = OnClickListener { v ->
        v.post { v.visibility = View.INVISIBLE }
        if (conversation!!.isDomainBlocked) {
            BlockContactDialog.show(activity, conversation!!)
        } else {
            unblockConversation(conversation)
        }
    }

    val blockClickListener = OnClickListener {
        showBlockSubmenu(it)
    }

    val addBackClickListener = OnClickListener {
        val contact = if (conversation == null) null else conversation!!.contact
        if (contact != null) {
            activity!!.xmppConnectionService.createContact(contact, true)
            activity!!.switchToContactDetails(contact)
        }
    }

    val longPressBlockListener = View.OnLongClickListener {
        showBlockSubmenu(it)
    }

    val allowPresenceSubscription = OnClickListener {
        val contact = if (conversation == null) null else conversation!!.contact
        if (contact != null) {
            activity!!.xmppConnectionService.sendPresencePacket(
                contact.account,
                activity!!.xmppConnectionService.presenceGenerator
                    .sendPresenceUpdatesTo(contact)
            )
            hideSnackbar()
        }
    }

    val clickToDecryptListener = OnClickListener {
        val pendingIntent = conversation!!.account.pgpDecryptionService.pendingIntent
        if (pendingIntent != null) {
            try {
                getActivity().startIntentSenderForResult(
                    pendingIntent.intentSender,
                    REQUEST_DECRYPT_PGP, null,
                    0,
                    0,
                    0
                )
            } catch (e: SendIntentException) {
                Toast.makeText(
                    getActivity(),
                    R.string.unable_to_connect_to_keychain,
                    Toast.LENGTH_SHORT
                ).show()
                conversation!!.account.pgpDecryptionService.continueDecryption(true)
            }

        }
        updateSnackBar(conversation!!)
    }

    val editorActionListener = OnEditorActionListener { v, actionId, event ->
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            val imm =
                activity!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (imm.isFullscreenMode) {
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
            sendMessage()
            true
        } else
            false
    }

    val scrollButtonListener = OnClickListener {
        stopScrolling()
        setSelection(binding!!.messagesView.count - 1, true)
    }

    val sendButtonListener = OnClickListener { v ->
        val tag = v.tag
        if (tag is SendButtonAction) {
            when (tag) {
                SendButtonAction.TAKE_PHOTO, SendButtonAction.RECORD_VIDEO, SendButtonAction.SEND_LOCATION, SendButtonAction.RECORD_VOICE, SendButtonAction.CHOOSE_PICTURE -> attachFile(
                    tag.toChoice()
                )
                SendButtonAction.CANCEL -> if (conversation != null) {
                    if (conversation!!.setCorrectingMessage(null)) {
                        binding!!.textinput.setText("")
                        binding!!.textinput.append(conversation!!.draftMessage)
                        conversation!!.draftMessage = null
                    } else if (conversation!!.mode == Conversation.MODE_MULTI) {
                        conversation!!.nextCounterpart = null
                    }
                    updateChatMsgHint()
                    updateSendButton()
                    updateEditablity()
                }
                else -> sendMessage()
            }
        } else {
            sendMessage()
        }
    }

    val scrollPosition: ScrollState?
        get() {
            val listView = if (this.binding == null) null else this.binding!!.messagesView
            if (listView == null || listView.count == 0 || listView.lastVisiblePosition == listView.count - 1) {
                return null
            } else {
                val pos = listView.firstVisiblePosition
                val view = listView.getChildAt(0)
                return if (view == null) {
                    null
                } else {
                    ScrollState(pos, view.top)
                }
            }
        }

    //should not happen if we synchronize properly. however if that fails we just gonna try item -1
    val lastVisibleMessageUuid: String?
        get() {
            if (binding == null) {
                return null
            }
            synchronized(this.messageList) {
                val pos = binding!!.messagesView.lastVisiblePosition
                if (pos >= 0) {
                    var message: Message? = null
                    for (i in pos downTo 0) {
                        try {
                            message = binding!!.messagesView.getItemAtPosition(i) as Message
                        } catch (e: IndexOutOfBoundsException) {
                            continue
                        }

                        if (message.type != Message.TYPE_STATUS) {
                            break
                        }
                    }
                    if (message != null) {
                        while (message!!.next() != null && message.next()!!.wasMergedIntoPrevious()) {
                            message = message.next()
                        }
                        return message.uuid
                    }
                }
            }
            return null
        }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handleActivityResult(requestCode, resultCode, data)
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        this.activity = onAttach.invoke(activity)
    }

    override fun onDetach() {
        super.onDetach()
        this.activity = null //TODO maybe not a good idea since some callbacks really need it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        menuInflater: MenuInflater
    ) {
        onCreateOptionsMenu.invoke(menu, menuInflater)
        super.onCreateOptionsMenu(menu, menuInflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = OnCreateView(
        activity = activity!!,
        fragment = this
    ).invoke(inflater, container, savedInstanceState)

    override fun onCreateContextMenu(
        menu: ContextMenu,
        view: View,
        menuInfo: ContextMenuInfo
    ) {
        super.onCreateContextMenu(menu, view, menuInfo)
        onCreateContextMenu.invoke(menu, view, menuInfo)
    }

    override fun onContextItemSelected(
        item: MenuItem
    ): Boolean = onContextItemSelected.invoke(item) || super.onContextItemSelected(item)

    override fun onOptionsItemSelected(
        item: MenuItem
    ): Boolean = onOptionsItemSelected.invoke(item) || super.onOptionsItemSelected(item)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) = onRequestPermissionsResult.invoke(
        requestCode,
        permissions,
        grantResults
    )

    override fun onResume() {
        super.onResume()
        onResume.invoke()
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        (getActivity() as? ConversationsActivity)?.clearPendingViewIntent()
        super.startActivityForResult(intent, requestCode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        onSaveInstanceState.invoke(outState)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        onActivityCreated.invoke(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        onStart.invoke()
    }

    override fun onStop() {
        super.onStop()
        onStop.invoke()
    }

    override fun refresh() {
        refresh.invoke()
    }

    override fun onEnterPressed(): Boolean =
        onEnterPressed.invoke()

    override fun onTypingStarted() {
        onTypingStarted.invoke()
    }

    override fun onTypingStopped() {
        onTypingStopped.invoke()
    }

    override fun onTextDeleted() {
        onTextDeleted.invoke()
    }

    override fun onTextChanged() {
        onTextChanged.invoke()
    }

    override fun onTabPressed(repeated: Boolean): Boolean =
        onTabPressed.invoke(repeated)

    override fun onBackendConnected() {
        Timber.d("ConversationFragment.onBackendConnected()")
        onBackendConnected.invoke()
    }

    override fun onContactPictureLongClicked(v: View, message: Message) {
        onContactPictureLongClicked.invoke(v, message)
    }

    override fun onContactPictureClicked(message: Message) {
        onContactPictureClicked.invoke(message)
    }
}