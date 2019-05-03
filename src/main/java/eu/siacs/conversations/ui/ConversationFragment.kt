package eu.siacs.conversations.ui

import android.Manifest
import android.app.Activity
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.content.IntentSender.SendIntentException
import android.net.Uri
import android.os.Bundle
import android.support.annotation.IdRes
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


class ConversationFragment : XmppFragment(), EditMessage.KeyboardListener,
    MessageAdapter.OnContactPictureLongClicked,
    MessageAdapter.OnContactPictureClicked {

    @Inject
    lateinit var commitAttachments: CommitAttachments
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
    lateinit var appendText: AppendText
    @Inject
    lateinit var attachEditorContentToConversation: AttachEditorContentToConversation
    @Inject
    lateinit var attachFile: AttachFile
    @Inject
    lateinit var cleanUris: CleanUris
    @Inject
    lateinit var clearPending: ClearPending
    @Inject
    lateinit var createNewConnection: CreateNewConnection
    @Inject
    lateinit var encryptTextMessage: EncryptTextMessage
    @Inject
    lateinit var extractUris: ExtractUris
    @Inject
    lateinit var findAndReInitByUuidOrArchive: FindAndReInitByUuidOrArchive
    @Inject
    lateinit var fireReadEvent: FireReadEvent
    @Inject
    lateinit var hasMamSupport: HasMamSupport
    @Inject
    lateinit var hasPermissions: HasPermissions
    @Inject
    lateinit var hideSnackbar: HideSnackbar
    @Inject
    lateinit var hideUnreadMessagesCount: HideUnreadMessagesCount
    @Inject
    lateinit var highlightInConference: HighlightInConference
    @Inject
    lateinit var messageSent: MessageSent
    @Inject
    lateinit var privateMessageWith: PrivateMessageWith
    @Inject
    lateinit var processExtras: ProcessExtras
    @Inject
    lateinit var quoteText: QuoteText
    @Inject
    lateinit var refresh: Refresh
    @Inject
    lateinit var reInit: ReInit
    @Inject
    lateinit var resetUnreadMessagesCount: ResetUnreadMessagesCount
    @Inject
    lateinit var saveMessageDraftStopAudioPlayer: SaveMessageDraftStopAudioPlayer
    @Inject
    lateinit var scrolledToBottom: ScrolledToBottom
    @Inject
    lateinit var sendPgpMessage: SendPgpMessage
    @Inject
    lateinit var setSelection: SetSelection
    @Inject
    lateinit var setupIme: SetupIme
    @Inject
    lateinit var showBlockSubmenu: ShowBlockSubmenu
    @Inject
    lateinit var showLoadMoreMessages: ShowLoadMoreMessages
    @Inject
    lateinit var showSnackbar: ShowSnackbar
    @Inject
    lateinit var startDownloadable: StartDownloadable
    @Inject
    lateinit var startPendingIntent: StartPendingIntent
    @Inject
    lateinit var stopScrolling: StopScrolling
    @Inject
    lateinit var storeNextMessage: StoreNextMessage
    @Inject
    lateinit var trustKeysIfNeeded: TrustKeysIfNeeded
    @Inject
    lateinit var unblockConversation: unblockConversation
    @Inject
    lateinit var updateChatMsgHint: UpdateChatMsgHint
    @Inject
    lateinit var updateChatState: UpdateChatState
    @Inject
    lateinit var updateDateSeparators: UpdateDateSeparators
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
    @Inject
    lateinit var onAttach: OnAttach
    @Inject
    lateinit var onCreateView: OnCreateView
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

    val messageList = ArrayList<Message>()
    val postponedActivityResult = PendingItem<ActivityResult>()
    val pendingConversationsUuid = PendingItem<String>()
    val pendingMediaPreviews = PendingItem<ArrayList<Attachment>>()
    val pendingExtras = PendingItem<Bundle>()
    val pendingTakePhotoUri = PendingItem<Uri>()
    val pendingScrollState = PendingItem<ScrollState>()
    val pendingLastMessageUuid = PendingItem<String>()
    val pendingMessage = PendingItem<Message>()
    var mPendingEditorContent: Uri? = null
    lateinit var messageListAdapter: MessageAdapter
    var mediaPreviewAdapter: MediaPreviewAdapter? = null
    var lastMessageUuid: String? = null
    var conversation: Conversation? = null
        set
    var binding: FragmentConversationBinding? = null
    var messageLoaderToast: Toast? = null
    var activity: ConversationsActivity? = null
    var reInitRequiredOnStart = true
    val clickToMuc = OnClickListener {
        val intent = Intent(getActivity(), ConferenceDetailsActivity::class.java)
        intent.action = ConferenceDetailsActivity.ACTION_VIEW_MUC
        intent.putExtra("uuid", conversation!!.uuid)
        startActivity(intent)
    }
    val leaveMuc =
        OnClickListener { activity!!.xmppConnectionService.archiveConversation(conversation) }
    val joinMuc = OnClickListener { activity!!.xmppConnectionService.joinMuc(conversation) }

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
    val mOnScrollListener = object : OnScrollListener {

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
    val mEditorContentListener =
        EditMessage.OnCommitContentListener { inputContentInfo, flags, opts, contentMimeTypes ->
            // try to get permission to read the image, if applicable
            if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                try {
                    inputContentInfo.requestPermission()
                } catch (e: Exception) {
                    Log.e(Config.LOGTAG, "InputContentInfoCompat#requestPermission() failed.", e)
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
                mPendingEditorContent = inputContentInfo.contentUri
            }
            true
        }
    var selectedMessage: Message? = null
    val mEnableAccountListener = OnClickListener {
        val account = if (conversation == null) null else conversation!!.account
        if (account != null) {
            account.setOption(Account.OPTION_DISABLED, false)
            activity!!.xmppConnectionService.updateAccount(account)
        }
    }
    val mUnblockClickListener = OnClickListener { v ->
        v.post { v.visibility = View.INVISIBLE }
        if (conversation!!.isDomainBlocked) {
            BlockContactDialog.show(activity, conversation!!)
        } else {
            unblockConversation(conversation)
        }
    }
    val mBlockClickListener = OnClickListener { this.showBlockSubmenu(it) }
    val mAddBackClickListener = OnClickListener {
        val contact = if (conversation == null) null else conversation!!.contact
        if (contact != null) {
            activity!!.xmppConnectionService.createContact(contact, true)
            activity!!.switchToContactDetails(contact)
        }
    }
    val mLongPressBlockListener = View.OnLongClickListener { this.showBlockSubmenu(it) }
    val mAllowPresenceSubscription = OnClickListener {
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
    var clickToDecryptListener: OnClickListener = OnClickListener {
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
    val mSendingPgpMessage = AtomicBoolean(false)
    val mEditorActionListener = OnEditorActionListener { v, actionId, event ->
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
    val mScrollButtonListener = OnClickListener {
        stopScrolling()
        setSelection(binding!!.messagesView.count - 1, true)
    }
    val mSendButtonListener = OnClickListener { v ->
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
    var completionIndex = 0
    var lastCompletionLength = 0
    var incomplete: String? = null
    var lastCompletionCursor: Int = 0
    var firstWord = false
    var mPendingDownloadableMessage: Message? = null

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
    ) = onCreateOptionsMenu.invoke(menu, menuInflater)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = onCreateView.invoke(inflater, container)

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenuInfo
    ) = onCreateContextMenu.invoke(menu, v, menuInfo)

    override fun onContextItemSelected(
        item: MenuItem
    ): Boolean = onContextItemSelected.invoke(item)

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
        binding!!.messagesView.post { this.fireReadEvent() }
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

    companion object {


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

        @JvmStatic
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

        @JvmStatic
        fun startStopPending(activity: Activity) {
            val fragment = findConversationFragment(activity)
            fragment?.messageListAdapter?.startStopPending()
        }

        @JvmStatic
        fun downloadFile(activity: Activity, message: Message) {
            val fragment = findConversationFragment(activity)
            fragment?.startDownloadable?.invoke(message)
        }

        @JvmStatic
        fun registerPendingMessage(activity: Activity, message: Message) {
            val fragment = findConversationFragment(activity)
            fragment?.pendingMessage?.push(message)
        }

        @JvmStatic
        fun openPendingMessage(activity: Activity) {
            val fragment = findConversationFragment(activity)
            if (fragment != null) {
                val message = fragment.pendingMessage.pop()
                if (message != null) {
                    fragment.messageListAdapter.openDownloadable(message)
                }
            }
        }

        @JvmStatic
        fun getConversation(activity: Activity): Conversation? {
            return getConversation(activity, R.id.secondary_fragment)
        }

        @JvmStatic
        fun getConversation(activity: Activity, @IdRes res: Int): Conversation? {
            val fragment = activity.fragmentManager.findFragmentById(res)
            return if (fragment != null && fragment is ConversationFragment) {
                fragment.conversation
            } else {
                null
            }
        }

        @JvmStatic
        operator fun get(activity: Activity): ConversationFragment? {
            val fragmentManager = activity.fragmentManager
            var fragment: Fragment? = fragmentManager.findFragmentById(R.id.main_fragment)
            if (fragment != null && fragment is ConversationFragment) {
                return fragment
            } else {
                fragment = fragmentManager.findFragmentById(R.id.secondary_fragment)
                return if (fragment != null && fragment is ConversationFragment) fragment else null
            }
        }

        @JvmStatic
        fun getConversationReliable(activity: Activity): Conversation? {
            val conversation = getConversation(activity, R.id.secondary_fragment)
            return conversation ?: getConversation(activity, R.id.main_fragment)
        }

        @JvmStatic
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
    }
}