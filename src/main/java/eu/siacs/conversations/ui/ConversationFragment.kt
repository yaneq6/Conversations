package eu.siacs.conversations.ui

import android.Manifest
import android.app.Activity
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.content.IntentSender.SendIntentException
import android.databinding.DataBindingUtil
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.annotation.IdRes
import android.support.annotation.StringRes
import android.support.v13.view.inputmethod.InputConnectionCompat
import android.util.Log
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.view.View.OnClickListener
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.AbsListView.OnScrollListener
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView.OnEditorActionListener
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.conversation.command.*
import eu.siacs.conversations.feature.conversation.di.ConversationModule
import eu.siacs.conversations.feature.conversation.di.DaggerConversationComponent
import eu.siacs.conversations.feature.conversation.query.GetIndexOf
import eu.siacs.conversations.feature.conversations.di.ActivityModule
import eu.siacs.conversations.services.QuickConversationsService
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.XmppActivity.REQUEST_INVITE_TO_CONVERSATION
import eu.siacs.conversations.ui.adapter.MediaPreviewAdapter
import eu.siacs.conversations.ui.adapter.MessageAdapter
import eu.siacs.conversations.ui.util.*
import eu.siacs.conversations.ui.util.SoftKeyboardUtils.hideSoftKeyboard
import eu.siacs.conversations.ui.widget.EditMessage
import eu.siacs.conversations.utils.AccountUtils
import eu.siacs.conversations.utils.PermissionUtils.*
import eu.siacs.conversations.utils.QuickLoader
import eu.siacs.conversations.utils.StylingHelper
import eu.siacs.conversations.xmpp.chatstate.ChatState
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject


class ConversationFragment : XmppFragment(), EditMessage.KeyboardListener, MessageAdapter.OnContactPictureLongClicked,
    MessageAdapter.OnContactPictureClicked {

    @Inject lateinit var attachFileToConversation: AttachFileToConversation
    @Inject lateinit var commitAttachments: CommitAttachments
    @Inject lateinit var getIndexOf: GetIndexOf
    @Inject lateinit var setScrollPosition: SetScrollPosition
    @Inject lateinit var toggleScrollDownButton: ToggleScrollDownButton
    @Inject lateinit var hidePrepareFileToast: HidePrepareFileToast
    @Inject lateinit var sendMessage: SendMessage
    @Inject lateinit var handleActivityResult: HandleActivityResult
    @Inject lateinit var toggleInputMethod: ToggleInputMethod
    @Inject lateinit var appendText: AppendText
    @Inject lateinit var attachEditorContentToConversation: AttachEditorContentToConversation
    @Inject lateinit var attachFile: AttachFile
    @Inject lateinit var attachImageToConversation: AttachImageToConversation
    @Inject lateinit var attachLocationToConversation: AttachLocationToConversation
    @Inject lateinit var cancelTransmission: CancelTransmission
    @Inject lateinit var cleanUris: CleanUris
    @Inject lateinit var clearHistoryDialog: ClearHistoryDialog
    @Inject lateinit var clearPending: ClearPending
    @Inject lateinit var correctMessage: CorrectMessage
    @Inject lateinit var createNewConnection: CreateNewConnection
    @Inject lateinit var deleteFile: DeleteFile
    @Inject lateinit var doneSendingPgpMessage: DoneSendingPgpMessage
    @Inject lateinit var encryptTextMessage: EncryptTextMessage
    @Inject lateinit var extractUris: ExtractUris
    @Inject lateinit var findAndReInitByUuidOrArchive: FindAndReInitByUuidOrArchive
    @Inject lateinit var fireReadEvent: FireReadEvent
    @Inject lateinit var getMaxHttpUploadSize: GetMaxHttpUploadSize
    @Inject lateinit var handleAttachmentSelection: HandleAttachmentSelection
    @Inject lateinit var handleEncryptionSelection: HandleEncryptionSelection
    @Inject lateinit var hasMamSupport: HasMamSupport
    @Inject lateinit var hasPermissions: HasPermissions
    @Inject lateinit var hideSnackbar: HideSnackbar
    @Inject lateinit var hideUnreadMessagesCount: HideUnreadMessagesCount
    @Inject lateinit var highlightInConference: HighlightInConference
    @Inject lateinit var messageSent: MessageSent
    @Inject lateinit var muteConversationDialog: MuteConversationDialog
    @Inject lateinit var openWith: OpenWith
    @Inject lateinit var populateContextMenu: PopulateContextMenu
    @Inject lateinit var privateMessageWith: PrivateMessageWith
    @Inject lateinit var processExtras: ProcessExtras
    @Inject lateinit var quoteMessage: QuoteMessage
    @Inject lateinit var quoteText: QuoteText
    @Inject lateinit var refresh: Refresh
    @Inject lateinit var reInit: ReInit
    @Inject lateinit var resendMessage: ResendMessage
    @Inject lateinit var resetUnreadMessagesCount: ResetUnreadMessagesCount
    @Inject lateinit var retryDecryption: RetryDecryption
    @Inject lateinit var saveMessageDraftStopAudioPlayer: SaveMessageDraftStopAudioPlayer
    @Inject lateinit var scrolledToBottom: ScrolledToBottom
    @Inject lateinit var selectPresenceToAttachFile: SelectPresenceToAttachFile
    @Inject lateinit var sendPgpMessage: SendPgpMessage
    @Inject lateinit var setSelection: SetSelection
    @Inject lateinit var setupIme: SetupIme
    @Inject lateinit var showBlockSubmenu: ShowBlockSubmenu
    @Inject lateinit var showErrorMessage: ShowErrorMessage
    @Inject lateinit var showLoadMoreMessages: ShowLoadMoreMessages
    @Inject lateinit var showNoPgpKeyDialog: ShowNoPGPKeyDialog
    @Inject lateinit var showSnackbar: ShowSnackbar
    @Inject lateinit var startDownloadable: StartDownloadable
    @Inject lateinit var startPendingIntent: StartPendingIntent
    @Inject lateinit var stopScrolling: StopScrolling
    @Inject lateinit var storeNextMessage: StoreNextMessage
    @Inject lateinit var trustKeysIfNeeded: TrustKeysIfNeeded
    @Inject lateinit var unblockConversation: unblockConversation
    @Inject lateinit var unmuteConversation: UnmuteConversation
    @Inject lateinit var updateChatMsgHint: UpdateChatMsgHint
    @Inject lateinit var updateChatState: UpdateChatState
    @Inject lateinit var updateDateSeparators: UpdateDateSeparators
    @Inject lateinit var updateEditablity: UpdateEditablity
    @Inject lateinit var updateSendButton: UpdateSendButton
    @Inject lateinit var updateSnackBar: UpdateSnackBar
    @Inject lateinit var updateStatusMessages: UpdateStatusMessages

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
    val leaveMuc = OnClickListener { activity!!.xmppConnectionService.archiveConversation(conversation) }
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

        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
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
                                        val oldPosition = binding!!.messagesView.firstVisiblePosition
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
                                        this@ConversationFragment.conversation!!.populateWithMessages(this@ConversationFragment.messageList)
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
                                    messageLoaderToast = Toast.makeText(view.context, resId, Toast.LENGTH_LONG)
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
                        activity!!.getString(R.string.no_permission_to_access_x, inputContentInfo.description),
                        Toast.LENGTH_LONG
                    ).show()
                    return@OnCommitContentListener false
                }

            }
            if (hasPermissions(REQUEST_ADD_EDITOR_CONTENT, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
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
                Toast.makeText(getActivity(), R.string.unable_to_connect_to_keychain, Toast.LENGTH_SHORT).show()
                conversation!!.account.pgpDecryptionService.continueDecryption(true)
            }

        }
        updateSnackBar(conversation!!)
    }
    val mSendingPgpMessage = AtomicBoolean(false)
    val mEditorActionListener = OnEditorActionListener { v, actionId, event ->
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            val imm = activity!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
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
        val activityResult = ActivityResult.of(requestCode, resultCode, data)
        if (activity != null && activity!!.xmppConnectionService != null) {
            handleActivityResult(activityResult)
        } else {
            this.postponedActivityResult.push(activityResult)
        }
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        Log.d(Config.LOGTAG, "ConversationFragment.onAttach()")
        DaggerConversationComponent.builder()
            .activityModule(ActivityModule(activity))
            .conversationModule(ConversationModule(this))
            .build()(this)
        if (activity is ConversationsActivity) {
            this.activity = activity
        } else {
            throw IllegalStateException("Trying to attach fragment to activity that is not the ConversationsActivity")
        }
    }

    override fun onDetach() {
        super.onDetach()
        this.activity = null //TODO maybe not a good idea since some callbacks really need it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_conversation, menu)
        val menuMucDetails = menu.findItem(R.id.action_muc_details)
        val menuContactDetails = menu.findItem(R.id.action_contact_details)
        val menuInviteContact = menu.findItem(R.id.action_invite)
        val menuMute = menu.findItem(R.id.action_mute)
        val menuUnmute = menu.findItem(R.id.action_unmute)


        if (conversation != null) {
            if (conversation!!.mode == Conversation.MODE_MULTI) {
                menuContactDetails.isVisible = false
                menuInviteContact.isVisible = conversation!!.mucOptions.canInvite()
                menuMucDetails.setTitle(if (conversation!!.mucOptions.isPrivateAndNonAnonymous) R.string.action_muc_details else R.string.channel_details)
            } else {
                menuContactDetails.isVisible = !this.conversation!!.withSelf()
                menuMucDetails.isVisible = false
                val service = activity!!.xmppConnectionService
                menuInviteContact.isVisible =
                    service?.findConferenceServer(conversation!!.account) != null
            }
            if (conversation!!.isMuted) {
                menuMute.isVisible = false
            } else {
                menuUnmute.isVisible = false
            }
            ConversationMenuConfigurator.configureAttachmentMenu(conversation!!, menu)
            ConversationMenuConfigurator.configureEncryptionMenu(conversation!!, menu)
        }
        super.onCreateOptionsMenu(menu, menuInflater)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        this.binding = DataBindingUtil.inflate(inflater, R.layout.fragment_conversation, container, false)
        binding!!.root.setOnClickListener(null) //TODO why the fuck did we do this?

        binding!!.textinput.addTextChangedListener(StylingHelper.MessageEditorStyler(binding!!.textinput))

        binding!!.textinput.setOnEditorActionListener(mEditorActionListener)
        binding!!.textinput.setRichContentListener(arrayOf("image/*"), mEditorContentListener)

        binding!!.textSendButton.setOnClickListener(this.mSendButtonListener)

        binding!!.scrollToBottomButton.setOnClickListener(this.mScrollButtonListener)
        binding!!.messagesView.setOnScrollListener(mOnScrollListener)
        binding!!.messagesView.transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
        mediaPreviewAdapter = MediaPreviewAdapter(this)
        binding!!.mediaPreview.adapter = mediaPreviewAdapter
        messageListAdapter = MessageAdapter(getActivity() as XmppActivity, this.messageList)
        messageListAdapter.setOnContactPictureClicked(this)
        messageListAdapter.setOnContactPictureLongClicked(this)
        messageListAdapter.setOnQuoteListener { this.quoteText(it) }
        binding!!.messagesView.adapter = messageListAdapter

        registerForContextMenu(binding!!.messagesView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.binding!!.textinput.customInsertionActionModeCallback =
                EditMessageActionModeCallback(this.binding!!.textinput)
        }

        return binding!!.root
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo) {
        synchronized(this.messageList) {
            super.onCreateContextMenu(menu, v, menuInfo)
            val acmi = menuInfo as AdapterContextMenuInfo
            this.selectedMessage = this.messageList[acmi.position]
            populateContextMenu(menu)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.share_with -> {
                ShareUtil.share(activity, selectedMessage!!)
                return true
            }
            R.id.correct_message -> {
                correctMessage(selectedMessage!!)
                return true
            }
            R.id.copy_message -> {
                ShareUtil.copyToClipboard(activity!!, selectedMessage!!)
                return true
            }
            R.id.copy_link -> {
                ShareUtil.copyLinkToClipboard(activity, selectedMessage!!)
                return true
            }
            R.id.quote_message -> {
                quoteMessage(selectedMessage)
                return true
            }
            R.id.send_again -> {
                resendMessage(selectedMessage!!)
                return true
            }
            R.id.copy_url -> {
                ShareUtil.copyUrlToClipboard(activity, selectedMessage!!)
                return true
            }
            R.id.download_file -> {
                startDownloadable(selectedMessage)
                return true
            }
            R.id.cancel_transmission -> {
                cancelTransmission(selectedMessage!!)
                return true
            }
            R.id.retry_decryption -> {
                retryDecryption(selectedMessage!!)
                return true
            }
            R.id.delete_file -> {
                deleteFile(selectedMessage)
                return true
            }
            R.id.show_error_message -> {
                showErrorMessage(selectedMessage!!)
                return true
            }
            R.id.open_with -> {
                openWith(selectedMessage!!)
                return true
            }
            else -> return super.onContextItemSelected(item)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false
        } else if (conversation == null) {
            return super.onOptionsItemSelected(item)
        }
        when (item.itemId) {
            R.id.encryption_choice_axolotl, R.id.encryption_choice_pgp, R.id.encryption_choice_none -> handleEncryptionSelection(
                item
            )
            R.id.attach_choose_picture, R.id.attach_take_picture, R.id.attach_record_video, R.id.attach_choose_file, R.id.attach_record_voice, R.id.attach_location -> handleAttachmentSelection(
                item
            )
            R.id.action_archive -> activity!!.xmppConnectionService.archiveConversation(conversation)
            R.id.action_contact_details -> activity!!.switchToContactDetails(conversation!!.contact)
            R.id.action_muc_details -> {
                val intent = Intent(getActivity(), ConferenceDetailsActivity::class.java)
                intent.action = ConferenceDetailsActivity.ACTION_VIEW_MUC
                intent.putExtra("uuid", conversation!!.uuid)
                startActivity(intent)
            }
            R.id.action_invite -> startActivityForResult(
                ChooseContactActivity.create(activity, conversation!!),
                REQUEST_INVITE_TO_CONVERSATION
            )
            R.id.action_clear_history -> clearHistoryDialog(conversation!!)
            R.id.action_mute -> muteConversationDialog(conversation!!)
            R.id.action_unmute -> unmuteConversation(conversation!!)
            R.id.action_block, R.id.action_unblock -> {
                val activity = getActivity()
                if (activity is XmppActivity) {
                    BlockContactDialog.show(activity, conversation!!)
                }
            }
            else -> {
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.size > 0) {
            if (allGranted(grantResults)) {
                when (requestCode) {
                    REQUEST_START_DOWNLOAD -> if (this.mPendingDownloadableMessage != null) {
                        startDownloadable(this.mPendingDownloadableMessage)
                    }
                    REQUEST_ADD_EDITOR_CONTENT -> if (this.mPendingEditorContent != null) {
                        attachEditorContentToConversation(this.mPendingEditorContent!!)
                    }
                    REQUEST_COMMIT_ATTACHMENTS -> commitAttachments()
                    else -> attachFile(requestCode)
                }
            } else {
                @StringRes val res: Int
                val firstDenied = getFirstDenied(grantResults, permissions)
                if (Manifest.permission.RECORD_AUDIO == firstDenied) {
                    res = R.string.no_microphone_permission
                } else if (Manifest.permission.CAMERA == firstDenied) {
                    res = R.string.no_camera_permission
                } else {
                    res = R.string.no_storage_permission
                }
                Toast.makeText(getActivity(), res, Toast.LENGTH_SHORT).show()
            }
        }
        if (writeGranted(grantResults, permissions)) {
            if (activity != null && activity!!.xmppConnectionService != null) {
                activity!!.xmppConnectionService.restartFileObserver()
            }
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        binding!!.messagesView.post { this.fireReadEvent() }
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        val activity = getActivity()
        if (activity is ConversationsActivity) {
            activity.clearPendingViewIntent()
        }
        super.startActivityForResult(intent, requestCode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (conversation != null) {
            outState.putString(STATE_CONVERSATION_UUID, conversation!!.uuid)
            outState.putString(STATE_LAST_MESSAGE_UUID, lastMessageUuid)
            val uri = pendingTakePhotoUri.peek()
            if (uri != null) {
                outState.putString(STATE_PHOTO_URI, uri.toString())
            }
            val scrollState = scrollPosition
            if (scrollState != null) {
                outState.putParcelable(STATE_SCROLL_POSITION, scrollState)
            }
            val attachments = if (mediaPreviewAdapter == null) ArrayList() else mediaPreviewAdapter!!.attachments
            if (attachments.size > 0) {
                outState.putParcelableArrayList(STATE_MEDIA_PREVIEWS, attachments)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (savedInstanceState == null) {
            return
        }
        val uuid = savedInstanceState.getString(STATE_CONVERSATION_UUID)
        val attachments = savedInstanceState.getParcelableArrayList<Attachment>(STATE_MEDIA_PREVIEWS)
        pendingLastMessageUuid.push(savedInstanceState.getString(STATE_LAST_MESSAGE_UUID, null))
        if (uuid != null) {
            QuickLoader.set(uuid)
            this.pendingConversationsUuid.push(uuid)
            if (attachments != null && attachments.size > 0) {
                this.pendingMediaPreviews.push(attachments)
            }
            val takePhotoUri = savedInstanceState.getString(STATE_PHOTO_URI)
            if (takePhotoUri != null) {
                pendingTakePhotoUri.push(Uri.parse(takePhotoUri))
            }
            pendingScrollState.push(savedInstanceState.getParcelable(STATE_SCROLL_POSITION))
        }
    }

    override fun onStart() {
        super.onStart()
        if (this.reInitRequiredOnStart && this.conversation != null) {
            val extras = pendingExtras.pop()
            reInit(this.conversation, extras != null)
            if (extras != null) {
                processExtras(extras)
            }
        } else if (conversation == null && activity != null && activity!!.xmppConnectionService != null) {
            val uuid = pendingConversationsUuid.pop()
            Log.d(
                Config.LOGTAG,
                "ConversationFragment.onStart() - activity was bound but no conversation loaded. uuid=" + uuid!!
            )
            if (uuid != null) {
                findAndReInitByUuidOrArchive(uuid)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val activity = getActivity()
        messageListAdapter.unregisterListenerInAudioPlayer()
        if (activity == null || !activity.isChangingConfigurations) {
            hideSoftKeyboard(activity!!)
            messageListAdapter.stopAudioPlayer()
        }
        if (this.conversation != null) {
            val msg = this.binding!!.textinput.text!!.toString()
            storeNextMessage(msg)
            updateChatState(this.conversation!!, msg)
            this.activity!!.xmppConnectionService.notificationService.setOpenConversation(null)
        }
        this.reInitRequiredOnStart = true
    }

    override fun refresh() {
        if (this.binding == null) {
            Log.d(Config.LOGTAG, "ConversationFragment.refresh() skipped updated because view binding was null")
            return
        }
        if (this.conversation != null && this.activity != null && this.activity!!.xmppConnectionService != null) {
            if (!activity!!.xmppConnectionService.isConversationStillOpen(this.conversation)) {
                activity!!.onConversationArchived(this.conversation!!)
                return
            }
        }
        this.refresh(true)
    }

    override fun onEnterPressed(): Boolean {
        val p = PreferenceManager.getDefaultSharedPreferences(getActivity())
        val enterIsSend = p.getBoolean("enter_is_send", resources.getBoolean(R.bool.enter_is_send))
        return if (enterIsSend) {
            sendMessage()
            true
        } else {
            false
        }
    }

    override fun onTypingStarted() {
        val service = (if (activity == null) null else activity!!.xmppConnectionService) ?: return
        val status = conversation!!.account.status
        if (status == Account.State.ONLINE && conversation!!.setOutgoingChatState(ChatState.COMPOSING)) {
            service.sendChatState(conversation)
        }
        updateSendButton()
    }

    override fun onTypingStopped() {
        val service = (if (activity == null) null else activity!!.xmppConnectionService) ?: return
        val status = conversation!!.account.status
        if (status == Account.State.ONLINE && conversation!!.setOutgoingChatState(ChatState.PAUSED)) {
            service.sendChatState(conversation)
        }
    }

    override fun onTextDeleted() {
        val service = (if (activity == null) null else activity!!.xmppConnectionService) ?: return
        val status = conversation!!.account.status
        if (status == Account.State.ONLINE && conversation!!.setOutgoingChatState(Config.DEFAULT_CHATSTATE)) {
            service.sendChatState(conversation)
        }
        if (storeNextMessage()) {
            activity!!.onConversationsListItemUpdated()
        }
        updateSendButton()
    }

    override fun onTextChanged() {
        if (conversation != null && conversation!!.correctingMessage != null) {
            updateSendButton()
        }
    }

    override fun onTabPressed(repeated: Boolean): Boolean {
        if (conversation == null || conversation!!.mode == Conversation.MODE_SINGLE) {
            return false
        }
        if (repeated) {
            completionIndex++
        } else {
            lastCompletionLength = 0
            completionIndex = 0
            val content = this.binding!!.textinput.text!!.toString()
            lastCompletionCursor = this.binding!!.textinput.selectionEnd
            val start = if (lastCompletionCursor > 0) content.lastIndexOf(" ", lastCompletionCursor - 1) + 1 else 0
            firstWord = start == 0
            incomplete = content.substring(start, lastCompletionCursor)
        }
        val completions = ArrayList<String>()
        for (user in conversation!!.mucOptions.users) {
            val name = user.name
            if (name != null && name.startsWith(incomplete!!)) {
                completions.add(name + if (firstWord) ": " else " ")
            }
        }
        Collections.sort(completions)
        if (completions.size > completionIndex) {
            val completion = completions[completionIndex].substring(incomplete!!.length)
            this.binding!!.textinput.editableText.delete(
                lastCompletionCursor,
                lastCompletionCursor + lastCompletionLength
            )
            this.binding!!.textinput.editableText.insert(lastCompletionCursor, completion)
            lastCompletionLength = completion.length
        } else {
            completionIndex = -1
            this.binding!!.textinput.editableText.delete(
                lastCompletionCursor,
                lastCompletionCursor + lastCompletionLength
            )
            lastCompletionLength = 0
        }
        return true
    }

    override fun onBackendConnected() {
        Log.d(Config.LOGTAG, "ConversationFragment.onBackendConnected()")
        val uuid = pendingConversationsUuid.pop()
        if (uuid != null) {
            if (!findAndReInitByUuidOrArchive(uuid)) {
                return
            }
        } else {
            if (!activity!!.xmppConnectionService.isConversationStillOpen(conversation)) {
                clearPending()
                activity!!.onConversationArchived(conversation!!)
                return
            }
        }
        val activityResult = postponedActivityResult.pop()
        if (activityResult != null) {
            handleActivityResult(activityResult)
        }
        clearPending()
    }

    override fun onContactPictureLongClicked(v: View, message: Message) {
        val fingerprint: String
        if (message.encryption == Message.ENCRYPTION_PGP || message.encryption == Message.ENCRYPTION_DECRYPTED) {
            fingerprint = "pgp"
        } else {
            fingerprint = message.fingerprint
        }
        val popupMenu = PopupMenu(getActivity(), v)
        val contact = message.contact
        if (message.status <= Message.STATUS_RECEIVED && (contact == null || !contact.isSelf)) {
            if (message.conversation.mode == Conversation.MODE_MULTI) {
                val cp = message.counterpart
                if (cp == null || cp.isBareJid) {
                    return
                }
                val tcp = message.trueCounterpart
                val userByRealJid =
                    if (tcp != null) conversation!!.mucOptions.findOrCreateUserByRealJid(tcp, cp) else null
                val user = userByRealJid ?: conversation!!.mucOptions.findUserByFullJid(cp)
                popupMenu.inflate(R.menu.muc_details_context)
                val menu = popupMenu.menu
                MucDetailsContextMenuHelper.configureMucDetailsContextMenu(activity, menu, conversation!!, user)
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    MucDetailsContextMenuHelper.onContextItemSelected(
                        menuItem,
                        user!!,
                        activity,
                        fingerprint
                    )
                }
            } else {
                popupMenu.inflate(R.menu.one_on_one_context)
                popupMenu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_contact_details -> activity!!.switchToContactDetails(message.contact, fingerprint)
                        R.id.action_show_qr_code -> activity!!.showQrCode("xmpp:" + message.contact!!.jid.asBareJid().toEscapedString())
                    }
                    true
                }
            }
        } else {
            popupMenu.inflate(R.menu.account_context)
            val menu = popupMenu.menu
            menu.findItem(R.id.action_manage_accounts).isVisible = QuickConversationsService.isConversations()
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_show_qr_code -> activity!!.showQrCode(conversation!!.account.shareableUri)
                    R.id.action_account_details -> activity!!.switchToAccount(
                        message.conversation.account,
                        fingerprint
                    )
                    R.id.action_manage_accounts -> AccountUtils.launchManageAccounts(activity)
                }
                true
            }
        }
        popupMenu.show()
    }

    override fun onContactPictureClicked(message: Message) {
        val fingerprint: String
        if (message.encryption == Message.ENCRYPTION_PGP || message.encryption == Message.ENCRYPTION_DECRYPTED) {
            fingerprint = "pgp"
        } else {
            fingerprint = message.fingerprint
        }
        val received = message.status <= Message.STATUS_RECEIVED
        if (received) {
            if (message.conversation is Conversation && message.conversation.mode == Conversation.MODE_MULTI) {
                val tcp = message.trueCounterpart
                val user = message.counterpart
                if (user != null && !user.isBareJid) {
                    val mucOptions = (message.conversation as Conversation).mucOptions
                    if (mucOptions.participating() || (message.conversation as Conversation).nextCounterpart != null) {
                        if (!mucOptions.isUserInRoom(user) && mucOptions.findUserByRealJid(tcp?.asBareJid()) == null) {
                            Toast.makeText(
                                getActivity(),
                                activity!!.getString(R.string.user_has_left_conference, user.resource),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        highlightInConference(user.resource)
                    } else {
                        Toast.makeText(getActivity(), R.string.you_are_not_participating, Toast.LENGTH_SHORT).show()
                    }
                }
                return
            } else {
                if (!message.contact!!.isSelf) {
                    activity!!.switchToContactDetails(message.contact, fingerprint)
                    return
                }
            }
        }
        activity!!.switchToAccount(message.conversation.account, fingerprint)
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