package eu.siacs.conversations.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Fragment
import android.app.PendingIntent
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentSender.SendIntentException
import android.content.pm.PackageManager
import android.databinding.DataBindingUtil
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.support.annotation.IdRes
import android.support.annotation.StringRes
import android.support.v13.view.inputmethod.InputConnectionCompat
import android.support.v7.app.AlertDialog
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.view.View.OnClickListener
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.AbsListView.OnScrollListener
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.TextView.OnEditorActionListener
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.crypto.axolotl.AxolotlService
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.*
import eu.siacs.conversations.feature.conversation.command.*
import eu.siacs.conversations.feature.conversation.di.ConversationModule
import eu.siacs.conversations.feature.conversation.di.DaggerConversationComponent
import eu.siacs.conversations.feature.conversation.query.GetIndexOf
import eu.siacs.conversations.feature.conversations.di.ActivityModule
import eu.siacs.conversations.http.HttpDownloadConnection
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.services.QuickConversationsService
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.XmppActivity.EXTRA_ACCOUNT
import eu.siacs.conversations.ui.XmppActivity.REQUEST_INVITE_TO_CONVERSATION
import eu.siacs.conversations.ui.adapter.MediaPreviewAdapter
import eu.siacs.conversations.ui.adapter.MessageAdapter
import eu.siacs.conversations.ui.util.*
import eu.siacs.conversations.ui.util.SoftKeyboardUtils.hideSoftKeyboard
import eu.siacs.conversations.ui.widget.EditMessage
import eu.siacs.conversations.utils.*
import eu.siacs.conversations.utils.PermissionUtils.*
import eu.siacs.conversations.xmpp.chatstate.ChatState
import eu.siacs.conversations.xmpp.jingle.JingleConnection
import rocks.xmpp.addr.Jid
import timber.log.Timber
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject


class ConversationFragment : XmppFragment(), EditMessage.KeyboardListener, MessageAdapter.OnContactPictureLongClicked,
    MessageAdapter.OnContactPictureClicked {

    @Inject
    lateinit var attachFileToConversation: AttachFileToConversation
    @Inject
    lateinit var commitAttachments: CommitAttachments
    @Inject
    lateinit var getIndexOf: GetIndexOf
    @Inject
    lateinit var setScrollPosition: SetScrollPosition
    @Inject
    lateinit var toggleScrollDownButton: ToggleScrollDownButton
    @Inject
    lateinit var hidePrepareFileToast: HidePrepareFileToast
    @Inject
    lateinit var sendMessage: SendMessage
    @Inject
    lateinit var handleActivityResult: HandleActivityResult
    @Inject
    lateinit var toggleInputMethod: ToggleInputMethod

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


    fun attachLocationToConversation(conversation: Conversation?, uri: Uri) {
        if (conversation == null) {
            return
        }
        activity!!.xmppConnectionService.attachLocationToConversation(conversation, uri, object : UiCallback<Message> {

            override fun success(message: Message) {

            }

            override fun error(errorCode: Int, `object`: Message) {
                //TODO show possible pgp error
            }

            override fun userInputRequried(pi: PendingIntent, `object`: Message) {

            }
        })
    }

    fun attachEditorContentToConversation(uri: Uri) {
        mediaPreviewAdapter!!.addMediaPreviews(Attachment.of(getActivity(), uri, Attachment.Type.FILE))
        toggleInputMethod()
    }

    fun attachImageToConversation(conversation: Conversation?, uri: Uri) {
        if (conversation == null) {
            return
        }
        val prepareFileToast = Toast.makeText(getActivity(), getText(R.string.preparing_image), Toast.LENGTH_LONG)
        prepareFileToast.show()
        activity!!.delegateUriPermissionsToService(uri)
        activity!!.xmppConnectionService.attachImageToConversation(conversation, uri,
            object : UiCallback<Message> {

                override fun userInputRequried(pi: PendingIntent, `object`: Message) {
                    hidePrepareFileToast(prepareFileToast)
                }

                override fun success(message: Message) {
                    hidePrepareFileToast(prepareFileToast)
                }

                override fun error(error: Int, message: Message) {
                    hidePrepareFileToast(prepareFileToast)
                    activity!!.runOnUiThread { activity!!.replaceToast(getString(error)) }
                }
            })
    }


    fun trustKeysIfNeeded(requestCode: Int): Boolean {
        val axolotlService = conversation!!.account.axolotlService
        val targets = axolotlService.getCryptoTargets(conversation)
        val hasUnaccepted = !conversation!!.acceptedCryptoTargets.containsAll(targets)
        val hasUndecidedOwn = !axolotlService.getKeysWithTrust(FingerprintStatus.createActiveUndecided()).isEmpty()
        val hasUndecidedContacts =
            !axolotlService.getKeysWithTrust(FingerprintStatus.createActiveUndecided(), targets).isEmpty()
        val hasPendingKeys = !axolotlService.findDevicesWithoutSession(conversation).isEmpty()
        val hasNoTrustedKeys = axolotlService.anyTargetHasNoTrustedKeys(targets)
        val downloadInProgress = axolotlService.hasPendingKeyFetches(targets)
        if (hasUndecidedOwn || hasUndecidedContacts || hasPendingKeys || hasNoTrustedKeys || hasUnaccepted || downloadInProgress) {
            axolotlService.createSessionsIfNeeded(conversation)
            val intent = Intent(getActivity(), TrustKeysActivity::class.java)
            val contacts = arrayOfNulls<String>(targets.size)
            for (i in contacts.indices) {
                contacts[i] = targets[i].toString()
            }
            intent.putExtra("contacts", contacts)
            intent.putExtra(EXTRA_ACCOUNT, conversation!!.account.jid.asBareJid().toString())
            intent.putExtra("conversation", conversation!!.uuid)
            startActivityForResult(intent, requestCode)
            return true
        } else {
            return false
        }
    }

    fun updateChatMsgHint() {
        val multi = conversation!!.mode == Conversation.MODE_MULTI
        if (conversation!!.correctingMessage != null) {
            this.binding!!.textinput.setHint(R.string.send_corrected_message)
        } else if (multi && conversation!!.nextCounterpart != null) {
            this.binding!!.textinput.hint = getString(
                R.string.send_private_message_to,
                conversation!!.nextCounterpart.resource
            )
        } else if (multi && !conversation!!.mucOptions.participating()) {
            this.binding!!.textinput.setHint(R.string.you_are_not_participating)
        } else {
            this.binding!!.textinput.hint = UIHelper.getMessageHint(getActivity(), conversation!!)
            getActivity().invalidateOptionsMenu()
        }
    }

    fun setupIme() {
        this.binding!!.textinput.refreshIme()
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

    fun unblockConversation(conversation: Blockable?) {
        activity!!.xmppConnectionService.sendUnblockRequest(conversation)
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

    fun quoteText(text: String) {
        if (binding!!.textinput.isEnabled) {
            binding!!.textinput.insertAsQuote(text)
            binding!!.textinput.requestFocus()
            val inputMethodManager = getActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager?.showSoftInput(binding!!.textinput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun quoteMessage(message: Message?) {
        quoteText(MessageUtils.prepareQuote(message!!))
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo) {
        synchronized(this.messageList) {
            super.onCreateContextMenu(menu, v, menuInfo)
            val acmi = menuInfo as AdapterContextMenuInfo
            this.selectedMessage = this.messageList[acmi.position]
            populateContextMenu(menu)
        }
    }

    fun populateContextMenu(menu: ContextMenu) {
        val m = this.selectedMessage
        val t = m!!.transferable
        var relevantForCorrection = m
        while (relevantForCorrection!!.mergeable(relevantForCorrection.next())) {
            relevantForCorrection = relevantForCorrection.next()
        }
        if (m.type != Message.TYPE_STATUS) {

            if (m.encryption == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE || m.encryption == Message.ENCRYPTION_AXOLOTL_FAILED) {
                return
            }

            val deleted = m.isDeleted
            val encrypted =
                m.encryption == Message.ENCRYPTION_DECRYPTION_FAILED || m.encryption == Message.ENCRYPTION_PGP
            val receiving =
                m.status == Message.STATUS_RECEIVED && (t is JingleConnection || t is HttpDownloadConnection)
            activity!!.menuInflater.inflate(R.menu.message_context, menu)
            menu.setHeaderTitle(R.string.message_options)
            val openWith = menu.findItem(R.id.open_with)
            val copyMessage = menu.findItem(R.id.copy_message)
            val copyLink = menu.findItem(R.id.copy_link)
            val quoteMessage = menu.findItem(R.id.quote_message)
            val retryDecryption = menu.findItem(R.id.retry_decryption)
            val correctMessage = menu.findItem(R.id.correct_message)
            val shareWith = menu.findItem(R.id.share_with)
            val sendAgain = menu.findItem(R.id.send_again)
            val copyUrl = menu.findItem(R.id.copy_url)
            val downloadFile = menu.findItem(R.id.download_file)
            val cancelTransmission = menu.findItem(R.id.cancel_transmission)
            val deleteFile = menu.findItem(R.id.delete_file)
            val showErrorMessage = menu.findItem(R.id.show_error_message)
            val showError =
                m.status == Message.STATUS_SEND_FAILED && m.errorMessage != null && Message.ERROR_MESSAGE_CANCELLED != m.errorMessage
            if (!m.isFileOrImage && !encrypted && !m.isGeoUri && !m.treatAsDownloadable()) {
                copyMessage.isVisible = true
                quoteMessage.isVisible = !showError && MessageUtils.prepareQuote(m).length > 0
                val body = m.mergedBody.toString()
                if (ShareUtil.containsXmppUri(body)) {
                    copyLink.setTitle(R.string.copy_jabber_id)
                    copyLink.isVisible = true
                } else if (Patterns.AUTOLINK_WEB_URL.matcher(body).find()) {
                    copyLink.isVisible = true
                }
            }
            if (m.encryption == Message.ENCRYPTION_DECRYPTION_FAILED && !deleted) {
                retryDecryption.isVisible = true
            }
            if (!showError
                && relevantForCorrection.type == Message.TYPE_TEXT
                && !m.isGeoUri
                && relevantForCorrection.isLastCorrectableMessage
                && m.conversation is Conversation
            ) {
                correctMessage.isVisible = true
            }
            if (m.isFileOrImage && !deleted && !receiving || m.type == Message.TYPE_TEXT && !m.treatAsDownloadable()) {
                shareWith.isVisible = true
            }
            if (m.status == Message.STATUS_SEND_FAILED) {
                sendAgain.isVisible = true
            }
            if (m.hasFileOnRemoteHost()
                || m.isGeoUri
                || m.treatAsDownloadable()
                || t is HttpDownloadConnection
            ) {
                copyUrl.isVisible = true
            }
            if (m.isFileOrImage && deleted && m.hasFileOnRemoteHost()) {
                downloadFile.isVisible = true
                downloadFile.title =
                    activity!!.getString(R.string.download_x_file, UIHelper.getFileDescriptionString(activity, m))
            }
            val waitingOfferedSending = (m.status == Message.STATUS_WAITING
                    || m.status == Message.STATUS_UNSEND
                    || m.status == Message.STATUS_OFFERED)
            val cancelable = t != null && !deleted || waitingOfferedSending && m.needsUploading()
            if (cancelable) {
                cancelTransmission.isVisible = true
            }
            if (m.isFileOrImage && !deleted && !cancelable) {
                val path = m.relativeFilePath
                if (path == null || !path.startsWith("/") || FileBackend.isInDirectoryThatShouldNotBeScanned(
                        getActivity(),
                        path
                    )
                ) {
                    deleteFile.isVisible = true
                    deleteFile.title =
                        activity!!.getString(R.string.delete_x_file, UIHelper.getFileDescriptionString(activity, m))
                }
            }
            if (showError) {
                showErrorMessage.isVisible = true
            }
            val mime = if (m.isFileOrImage) m.mimeType else null
            if (m.isGeoUri && GeoHelper.openInOsmAnd(getActivity(), m) || mime != null && mime.startsWith("audio/")) {
                openWith.isVisible = true
            }
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

    fun handleAttachmentSelection(item: MenuItem) {
        when (item.itemId) {
            R.id.attach_choose_picture -> attachFile(ATTACHMENT_CHOICE_CHOOSE_IMAGE)
            R.id.attach_take_picture -> attachFile(ATTACHMENT_CHOICE_TAKE_PHOTO)
            R.id.attach_record_video -> attachFile(ATTACHMENT_CHOICE_RECORD_VIDEO)
            R.id.attach_choose_file -> attachFile(ATTACHMENT_CHOICE_CHOOSE_FILE)
            R.id.attach_record_voice -> attachFile(ATTACHMENT_CHOICE_RECORD_VOICE)
            R.id.attach_location -> attachFile(ATTACHMENT_CHOICE_LOCATION)
        }
    }

    fun handleEncryptionSelection(item: MenuItem) {
        if (conversation == null) {
            return
        }
        val updated: Boolean
        when (item.itemId) {
            R.id.encryption_choice_none -> {
                updated = conversation!!.setNextEncryption(Message.ENCRYPTION_NONE)
                item.isChecked = true
            }
            R.id.encryption_choice_pgp -> if (activity!!.hasPgp()) {
                if (conversation!!.account.pgpSignature != null) {
                    updated = conversation!!.setNextEncryption(Message.ENCRYPTION_PGP)
                    item.isChecked = true
                } else {
                    updated = false
                    activity!!.announcePgp(conversation!!.account, conversation, null, activity!!.onOpenPGPKeyPublished)
                }
            } else {
                activity!!.showInstallPgpDialog()
                updated = false
            }
            R.id.encryption_choice_axolotl -> {
                Timber.d(AxolotlService.getLogprefix(conversation!!.account) + "Enabled axolotl for Contact " + conversation!!.contact.jid)
                updated = conversation!!.setNextEncryption(Message.ENCRYPTION_AXOLOTL)
                item.isChecked = true
            }
            else -> updated = conversation!!.setNextEncryption(Message.ENCRYPTION_NONE)
        }
        if (updated) {
            activity!!.xmppConnectionService.updateConversation(conversation)
        }
        updateChatMsgHint()
        getActivity().invalidateOptionsMenu()
        activity!!.refreshUi()
    }

    fun attachFile(attachmentChoice: Int) {
        if (attachmentChoice == ATTACHMENT_CHOICE_RECORD_VOICE) {
            if (!hasPermissions(
                    attachmentChoice,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO
                )
            ) {
                return
            }
        } else if (attachmentChoice == ATTACHMENT_CHOICE_TAKE_PHOTO || attachmentChoice == ATTACHMENT_CHOICE_RECORD_VIDEO) {
            if (!hasPermissions(
                    attachmentChoice,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA
                )
            ) {
                return
            }
        } else if (attachmentChoice != ATTACHMENT_CHOICE_LOCATION) {
            if (!hasPermissions(attachmentChoice, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                return
            }
        }
        try {
            activity!!.preferences.edit()
                .putString(RECENTLY_USED_QUICK_ACTION, SendButtonAction.of(attachmentChoice).toString())
                .apply()
        } catch (e: IllegalArgumentException) {
            //just do not save
        }

        val encryption = conversation!!.nextEncryption
        val mode = conversation!!.mode
        if (encryption == Message.ENCRYPTION_PGP) {
            if (activity!!.hasPgp()) {
                if (mode == Conversation.MODE_SINGLE && conversation!!.contact.pgpKeyId != 0L) {
                    activity!!.xmppConnectionService.pgpEngine!!.hasKey(
                        conversation!!.contact,
                        object : UiCallback<Contact> {

                            override fun userInputRequried(pi: PendingIntent, contact: Contact) {
                                startPendingIntent(pi, attachmentChoice)
                            }

                            override fun success(contact: Contact) {
                                selectPresenceToAttachFile(attachmentChoice)
                            }

                            override fun error(error: Int, contact: Contact) {
                                activity!!.replaceToast(getString(error))
                            }
                        })
                } else if (mode == Conversation.MODE_MULTI && conversation!!.mucOptions.pgpKeysInUse()) {
                    if (!conversation!!.mucOptions.everybodyHasKeys()) {
                        val warning = Toast.makeText(getActivity(), R.string.missing_public_keys, Toast.LENGTH_LONG)
                        warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
                        warning.show()
                    }
                    selectPresenceToAttachFile(attachmentChoice)
                } else {
                    showNoPGPKeyDialog(false, DialogInterface.OnClickListener { _, _ ->
                        conversation!!.nextEncryption = Message.ENCRYPTION_NONE
                        activity!!.xmppConnectionService.updateConversation(conversation)
                        selectPresenceToAttachFile(attachmentChoice)
                    })
                }
            } else {
                activity!!.showInstallPgpDialog()
            }
        } else {
            selectPresenceToAttachFile(attachmentChoice)
        }
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

    fun startDownloadable(message: Message?) {
        if (!hasPermissions(REQUEST_START_DOWNLOAD, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            this.mPendingDownloadableMessage = message
            return
        }
        val transferable = message!!.transferable
        if (transferable != null) {
            if (transferable is TransferablePlaceholder && message.hasFileOnRemoteHost()) {
                createNewConnection(message)
                return
            }
            if (!transferable.start()) {
                Log.d(Config.LOGTAG, "type: " + transferable.javaClass.name)
                Toast.makeText(getActivity(), R.string.not_connected_try_again, Toast.LENGTH_SHORT).show()
            }
        } else if (message.treatAsDownloadable() || message.hasFileOnRemoteHost()) {
            createNewConnection(message)
        }
    }

    fun createNewConnection(message: Message) {
        if (!activity!!.xmppConnectionService.httpConnectionManager.checkConnection(message)) {
            Toast.makeText(getActivity(), R.string.not_connected_try_again, Toast.LENGTH_SHORT).show()
            return
        }
        activity!!.xmppConnectionService.httpConnectionManager.createNewDownloadConnection(message, true)
    }

    @SuppressLint("InflateParams")
    protected fun clearHistoryDialog(conversation: Conversation) {
        val builder = AlertDialog.Builder(getActivity())
        builder.setTitle(getString(R.string.clear_conversation_history))
        val dialogView = getActivity().layoutInflater.inflate(R.layout.dialog_clear_history, null)
        val endConversationCheckBox = dialogView.findViewById<CheckBox>(R.id.end_conversation_checkbox)
        builder.setView(dialogView)
        builder.setNegativeButton(getString(R.string.cancel), null)
        builder.setPositiveButton(getString(R.string.confirm)) { dialog, which ->
            this.activity!!.xmppConnectionService.clearConversationHistory(conversation)
            if (endConversationCheckBox.isChecked) {
                this.activity!!.xmppConnectionService.archiveConversation(conversation)
                this.activity!!.onConversationArchived(conversation)
            } else {
                activity!!.onConversationsListItemUpdated()
                refresh()
            }
        }
        builder.create().show()
    }

    protected fun muteConversationDialog(conversation: Conversation) {
        val builder = AlertDialog.Builder(getActivity())
        builder.setTitle(R.string.disable_notifications)
        val durations = resources.getIntArray(R.array.mute_options_durations)
        val labels = arrayOfNulls<CharSequence>(durations.size)
        for (i in durations.indices) {
            if (durations[i] == -1) {
                labels[i] = getString(R.string.until_further_notice)
            } else {
                labels[i] = TimeframeUtils.resolve(activity, 1000L * durations[i])
            }
        }
        builder.setItems(labels) { dialog, which ->
            val till: Long
            if (durations[which] == -1) {
                till = java.lang.Long.MAX_VALUE
            } else {
                till = System.currentTimeMillis() + durations[which] * 1000
            }
            conversation.setMutedTill(till)
            activity!!.xmppConnectionService.updateConversation(conversation)
            activity!!.onConversationsListItemUpdated()
            refresh()
            getActivity().invalidateOptionsMenu()
        }
        builder.create().show()
    }

    fun hasPermissions(requestCode: Int, vararg permissions: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val missingPermissions = ArrayList<String>()
            for (permission in permissions) {
                if (Config.ONLY_INTERNAL_STORAGE && permission == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
                    continue
                }
                if (activity!!.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    missingPermissions.add(permission)
                }
            }
            if (missingPermissions.size == 0) {
                return true
            } else {
                requestPermissions(missingPermissions.toTypedArray(), requestCode)
                return false
            }
        } else {
            return true
        }
    }

    fun unmuteConversation(conversation: Conversation) {
        conversation.setMutedTill(0)
        this.activity!!.xmppConnectionService.updateConversation(conversation)
        this.activity!!.onConversationsListItemUpdated()
        refresh()
        getActivity().invalidateOptionsMenu()
    }

    protected fun selectPresenceToAttachFile(attachmentChoice: Int) {
        val account = conversation!!.account
        val callback = PresenceSelector.OnPresenceSelected {
            var intent = Intent()
            var chooser = false
            when (attachmentChoice) {
                ATTACHMENT_CHOICE_CHOOSE_IMAGE -> {
                    intent.action = Intent.ACTION_GET_CONTENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    intent.type = "image/*"
                    chooser = true
                }
                ATTACHMENT_CHOICE_RECORD_VIDEO -> intent.action = MediaStore.ACTION_VIDEO_CAPTURE
                ATTACHMENT_CHOICE_TAKE_PHOTO -> {
                    val uri = activity!!.xmppConnectionService.fileBackend.takePhotoUri
                    pendingTakePhotoUri.push(uri)
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.action = MediaStore.ACTION_IMAGE_CAPTURE
                }
                ATTACHMENT_CHOICE_CHOOSE_FILE -> {
                    chooser = true
                    intent.type = "*/*"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    intent.action = Intent.ACTION_GET_CONTENT
                }
                ATTACHMENT_CHOICE_RECORD_VOICE -> intent = Intent(getActivity(), RecordingActivity::class.java)
                ATTACHMENT_CHOICE_LOCATION -> intent = GeoHelper.getFetchIntent(activity)
            }
            if (intent.resolveActivity(getActivity().packageManager) != null) {
                if (chooser) {
                    startActivityForResult(
                        Intent.createChooser(intent, getString(R.string.perform_action_with)),
                        attachmentChoice
                    )
                } else {
                    startActivityForResult(intent, attachmentChoice)
                }
            }
        }
        if (account.httpUploadAvailable() || attachmentChoice == ATTACHMENT_CHOICE_LOCATION) {
            conversation!!.nextCounterpart = null
            callback.onPresenceSelected()
        } else {
            activity!!.selectPresence(conversation, callback)
        }
    }

    override fun onResume() {
        super.onResume()
        binding!!.messagesView.post { this.fireReadEvent() }
    }

    fun fireReadEvent() {
        if (activity != null && this.conversation != null) {
            val uuid = lastVisibleMessageUuid
            if (uuid != null) {
                activity!!.onConversationRead(this.conversation!!, uuid)
            }
        }
    }

    fun openWith(message: Message) {
        if (message.isGeoUri) {
            GeoHelper.view(getActivity(), message)
        } else {
            val file = activity!!.xmppConnectionService.fileBackend.getFile(message)
            ViewUtil.view(activity, file)
        }
    }

    fun showErrorMessage(message: Message) {
        val builder = AlertDialog.Builder(getActivity())
        builder.setTitle(R.string.error_message)
        val errorMessage = message.errorMessage
        val errorMessageParts = errorMessage?.split("\\u001f".toRegex())?.dropLastWhile { it.isEmpty() }?.toTypedArray()
            ?: arrayOfNulls<String>(0)
        val displayError: String?
        if (errorMessageParts.size == 2) {
            displayError = errorMessageParts[1]
        } else {
            displayError = errorMessage
        }
        builder.setMessage(displayError)
        builder.setNegativeButton(R.string.copy_to_clipboard) { dialog, which ->
            activity!!.copyTextToClipboard(displayError, R.string.error_message)
            Toast.makeText(activity, R.string.error_message_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
        builder.setPositiveButton(R.string.confirm, null)
        builder.create().show()
    }


    fun deleteFile(message: Message?) {
        val builder = AlertDialog.Builder(getActivity())
        builder.setNegativeButton(R.string.cancel, null)
        builder.setTitle(R.string.delete_file_dialog)
        builder.setMessage(R.string.delete_file_dialog_msg)
        builder.setPositiveButton(R.string.confirm) { dialog, which ->
            if (activity!!.xmppConnectionService.fileBackend.deleteFile(message)) {
                message!!.isDeleted = true
                activity!!.xmppConnectionService.updateMessage(message, false)
                activity!!.onConversationsListItemUpdated()
                refresh()
            }
        }
        builder.create().show()

    }

    fun resendMessage(message: Message) {
        if (message.isFileOrImage) {
            if (message.conversation !is Conversation) {
                return
            }
            val conversation = message.conversation as Conversation
            val file = activity!!.xmppConnectionService.fileBackend.getFile(message)
            if (file.exists() && file.canRead() || message.hasFileOnRemoteHost()) {
                val xmppConnection = conversation.account.xmppConnection
                if (!message.hasFileOnRemoteHost()
                    && xmppConnection != null
                    && conversation.mode == Conversational.MODE_SINGLE
                    && !xmppConnection.features.httpUpload(message.fileParams.size)
                ) {
                    activity!!.selectPresence(conversation) {
                        message.counterpart = conversation.nextCounterpart
                        activity!!.xmppConnectionService.resendFailedMessages(message)
                        Handler().post {
                            val size = messageList.size
                            this.binding!!.messagesView.setSelection(size - 1)
                        }
                    }
                    return
                }
            } else if (!Compatibility.hasStoragePermission(getActivity())) {
                Toast.makeText(activity, R.string.no_storage_permission, Toast.LENGTH_SHORT).show()
                return
            } else {
                Toast.makeText(activity, R.string.file_deleted, Toast.LENGTH_SHORT).show()
                message.isDeleted = true
                activity!!.xmppConnectionService.updateMessage(message, false)
                activity!!.onConversationsListItemUpdated()
                refresh()
                return
            }
        }
        activity!!.xmppConnectionService.resendFailedMessages(message)
        Handler().post {
            val size = messageList.size
            this.binding!!.messagesView.setSelection(size - 1)
        }
    }

    fun cancelTransmission(message: Message) {
        val transferable = message.transferable
        if (transferable != null) {
            transferable.cancel()
        } else if (message.status != Message.STATUS_RECEIVED) {
            activity!!.xmppConnectionService.markMessage(
                message,
                Message.STATUS_SEND_FAILED,
                Message.ERROR_MESSAGE_CANCELLED
            )
        }
    }

    fun retryDecryption(message: Message) {
        message.encryption = Message.ENCRYPTION_PGP
        activity!!.onConversationsListItemUpdated()
        refresh()
        conversation!!.account.pgpDecryptionService.decrypt(message, false)
    }

    fun privateMessageWith(counterpart: Jid) {
        if (conversation!!.setOutgoingChatState(Config.DEFAULT_CHATSTATE)) {
            activity!!.xmppConnectionService.sendChatState(conversation)
        }
        this.binding!!.textinput.setText("")
        this.conversation!!.nextCounterpart = counterpart
        updateChatMsgHint()
        updateSendButton()
        updateEditablity()
    }

    fun correctMessage(message: Message) {
        var message = message
        while (message.mergeable(message.next())) {
            message = message.next()
        }
        this.conversation!!.correctingMessage = message
        val editable = binding!!.textinput.text
        this.conversation!!.draftMessage = editable!!.toString()
        this.binding!!.textinput.setText("")
        this.binding!!.textinput.append(message.body)

    }

    fun highlightInConference(nick: String) {
        val editable = this.binding!!.textinput.text
        val oldString = editable!!.toString().trim { it <= ' ' }
        val pos = this.binding!!.textinput.selectionStart
        if (oldString.isEmpty() || pos == 0) {
            editable.insert(0, "$nick: ")
        } else {
            val before = editable[pos - 1]
            val after = if (editable.length > pos) editable[pos] else '\u0000'
            if (before == '\n') {
                editable.insert(pos, "$nick: ")
            } else {
                if (pos > 2 && editable.subSequence(pos - 2, pos).toString() == ": ") {
                    if (NickValidityChecker.check(
                            conversation,
                            Arrays.asList(
                                *editable.subSequence(
                                    0,
                                    pos - 2
                                ).toString().split(", ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                            )
                        )
                    ) {
                        editable.insert(pos - 2, ", $nick")
                        return
                    }
                }
                editable.insert(
                    pos,
                    (if (Character.isWhitespace(before)) "" else " ") + nick + if (Character.isWhitespace(after)) "" else " "
                )
                if (Character.isWhitespace(after)) {
                    this.binding!!.textinput.setSelection(this.binding!!.textinput.selectionStart + 1)
                }
            }
        }
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

    fun updateChatState(conversation: Conversation, msg: String) {
        val state = if (msg.length == 0) Config.DEFAULT_CHATSTATE else ChatState.PAUSED
        val status = conversation.account.status
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(state)) {
            activity!!.xmppConnectionService.sendChatState(conversation)
        }
    }

    fun saveMessageDraftStopAudioPlayer() {
        val previousConversation = this.conversation
        if (this.activity == null || this.binding == null || previousConversation == null) {
            return
        }
        Log.d(Config.LOGTAG, "ConversationFragment.saveMessageDraftStopAudioPlayer()")
        val msg = this.binding!!.textinput.text!!.toString()
        storeNextMessage(msg)
        updateChatState(this.conversation!!, msg)
        messageListAdapter.stopAudioPlayer()
        mediaPreviewAdapter!!.clearPreviews()
        toggleInputMethod()
    }

    fun reInit(conversation: Conversation, extras: Bundle?) {
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

    fun reInit(conversation: Conversation) {
        reInit(conversation, false)
    }

    fun reInit(conversation: Conversation?, hasExtras: Boolean): Boolean {
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
        Log.d(Config.LOGTAG, "reInit(hasExtras=" + java.lang.Boolean.toString(hasExtras) + ")")

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
        val participating = conversation.mode == Conversational.MODE_SINGLE || conversation.mucOptions.participating()
        if (participating) {
            this.binding!!.textinput.append(this.conversation!!.nextMessage)
        }
        this.binding!!.textinput.setKeyboardListener(this)
        messageListAdapter.updatePreferences()
        refresh(false)
        this.conversation!!.messagesLoaded.set(true)
        Log.d(Config.LOGTAG, "scrolledToBottomAndNoPending=" + java.lang.Boolean.toString(scrolledToBottomAndNoPending))

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

    fun resetUnreadMessagesCount() {
        lastMessageUuid = null
        hideUnreadMessagesCount()
    }

    fun hideUnreadMessagesCount() {
        if (this.binding == null) {
            return
        }
        this.binding!!.scrollToBottomButton.isEnabled = false
        this.binding!!.scrollToBottomButton.hide()
        this.binding!!.unreadCountCustomView.visibility = View.GONE
    }

    fun setSelection(pos: Int, jumpToBottom: Boolean) {
        ListViewUtils.setSelection(this.binding!!.messagesView, pos, jumpToBottom)
        this.binding!!.messagesView.post { ListViewUtils.setSelection(this.binding!!.messagesView, pos, jumpToBottom) }
        this.binding!!.messagesView.post { this.fireReadEvent() }
    }


    fun scrolledToBottom(): Boolean {
        return this.binding != null && scrolledToBottom(this.binding!!.messagesView)
    }

    fun processExtras(extras: Bundle) {
        val downloadUuid = extras.getString(ConversationsActivity.EXTRA_DOWNLOAD_UUID)
        val text = extras.getString(Intent.EXTRA_TEXT)
        val nick = extras.getString(ConversationsActivity.EXTRA_NICK)
        val asQuote = extras.getBoolean(ConversationsActivity.EXTRA_AS_QUOTE)
        val pm = extras.getBoolean(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, false)
        val doNotAppend = extras.getBoolean(ConversationsActivity.EXTRA_DO_NOT_APPEND, false)
        val uris = extractUris(extras)
        if (uris != null && uris.size > 0) {
            val cleanedUris = cleanUris(ArrayList(uris))
            mediaPreviewAdapter!!.addMediaPreviews(Attachment.of(getActivity(), cleanedUris))
            toggleInputMethod()
            return
        }
        if (nick != null) {
            if (pm) {
                val jid = conversation!!.jid
                try {
                    val next = Jid.of(jid.local, jid.domain, nick)
                    privateMessageWith(next)
                } catch (ignored: IllegalArgumentException) {
                    //do nothing
                }

            } else {
                val mucOptions = conversation!!.mucOptions
                if (mucOptions.participating() || conversation!!.nextCounterpart != null) {
                    highlightInConference(nick)
                }
            }
        } else {
            if (text != null && asQuote) {
                quoteText(text)
            } else {
                appendText(text, doNotAppend)
            }
        }
        val message = if (downloadUuid == null) null else conversation!!.findMessageWithFileAndUuid(downloadUuid)
        if (message != null) {
            startDownloadable(message)
        }
    }

    fun extractUris(extras: Bundle): List<Uri>? {
        val uris = extras.getParcelableArrayList<Uri>(Intent.EXTRA_STREAM)
        if (uris != null) {
            return uris
        }
        val uri = extras.getParcelable<Uri>(Intent.EXTRA_STREAM)
        return if (uri != null) {
            listOf(uri)
        } else {
            null
        }
    }

    fun cleanUris(uris: MutableList<Uri>): List<Uri> {
        val iterator = uris.iterator()
        while (iterator.hasNext()) {
            val uri = iterator.next()
            if (FileBackend.weOwnFile(getActivity(), uri)) {
                iterator.remove()
                Toast.makeText(getActivity(), R.string.security_violation_not_attaching_file, Toast.LENGTH_SHORT).show()
            }
        }
        return uris
    }

    fun showBlockSubmenu(view: View): Boolean {
        val conversation = conversation
        val jid = conversation!!.jid
        val showReject =
            !conversation.isWithStranger && conversation.contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
        val popupMenu = PopupMenu(getActivity(), view)
        popupMenu.inflate(R.menu.block)
        popupMenu.menu.findItem(R.id.block_contact).isVisible = jid.local != null
        popupMenu.menu.findItem(R.id.reject).isVisible = showReject
        popupMenu.setOnMenuItemClickListener { menuItem ->
            val blockable: Blockable = when (menuItem.itemId) {
                R.id.reject -> {
                    activity!!.xmppConnectionService.stopPresenceUpdatesTo(conversation.contact)
                    updateSnackBar(conversation)
                    popupMenu.setOnMenuItemClickListener { true }
                    conversation
                }
                R.id.block_domain -> conversation.account.roster.getContact(Jid.ofDomain(jid.domain))
                else -> conversation
            }
            BlockContactDialog.show(activity, blockable)
            true
        }
        popupMenu.show()
        return true
    }

    fun updateSnackBar(conversation: Conversation) {
        val account = conversation.account
        val connection = account.xmppConnection
        val mode = conversation.mode
        val contact = if (mode == Conversation.MODE_SINGLE) conversation.contact else null
        if (conversation.status == Conversation.STATUS_ARCHIVED) {
            return
        }
        if (account.status == Account.State.DISABLED) {
            showSnackbar(R.string.this_account_is_disabled, R.string.enable, this.mEnableAccountListener)
        } else if (conversation.isBlocked) {
            showSnackbar(R.string.contact_blocked, R.string.unblock, this.mUnblockClickListener)
        } else if (contact != null && !contact.showInRoster() && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
            showSnackbar(
                R.string.contact_added_you,
                R.string.add_back,
                this.mAddBackClickListener,
                this.mLongPressBlockListener
            )
        } else if (contact != null && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
            showSnackbar(
                R.string.contact_asks_for_presence_subscription,
                R.string.allow,
                this.mAllowPresenceSubscription,
                this.mLongPressBlockListener
            )
        } else if (mode == Conversation.MODE_MULTI
            && !conversation.mucOptions.online()
            && account.status == Account.State.ONLINE
        ) {
            when (conversation.mucOptions.error) {
                MucOptions.Error.NICK_IN_USE -> showSnackbar(R.string.nick_in_use, R.string.edit, clickToMuc)
                MucOptions.Error.NO_RESPONSE -> showSnackbar(R.string.joining_conference, 0, null)
                MucOptions.Error.SERVER_NOT_FOUND -> if (conversation.receivedMessagesCount() > 0) {
                    showSnackbar(R.string.remote_server_not_found, R.string.try_again, joinMuc)
                } else {
                    showSnackbar(R.string.remote_server_not_found, R.string.leave, leaveMuc)
                }
                MucOptions.Error.REMOTE_SERVER_TIMEOUT -> if (conversation.receivedMessagesCount() > 0) {
                    showSnackbar(R.string.remote_server_timeout, R.string.try_again, joinMuc)
                } else {
                    showSnackbar(R.string.remote_server_timeout, R.string.leave, leaveMuc)
                }
                MucOptions.Error.PASSWORD_REQUIRED -> showSnackbar(
                    R.string.conference_requires_password,
                    R.string.enter_password,
                    enterPassword
                )
                MucOptions.Error.BANNED -> showSnackbar(R.string.conference_banned, R.string.leave, leaveMuc)
                MucOptions.Error.MEMBERS_ONLY -> showSnackbar(
                    R.string.conference_members_only,
                    R.string.leave,
                    leaveMuc
                )
                MucOptions.Error.RESOURCE_CONSTRAINT -> showSnackbar(
                    R.string.conference_resource_constraint,
                    R.string.try_again,
                    joinMuc
                )
                MucOptions.Error.KICKED -> showSnackbar(R.string.conference_kicked, R.string.join, joinMuc)
                MucOptions.Error.UNKNOWN -> showSnackbar(R.string.conference_unknown_error, R.string.try_again, joinMuc)
                MucOptions.Error.INVALID_NICK -> {
                    showSnackbar(R.string.invalid_muc_nick, R.string.edit, clickToMuc)
                    showSnackbar(R.string.conference_shutdown, R.string.try_again, joinMuc)
                }
                MucOptions.Error.SHUTDOWN -> showSnackbar(R.string.conference_shutdown, R.string.try_again, joinMuc)
                MucOptions.Error.DESTROYED -> showSnackbar(R.string.conference_destroyed, R.string.leave, leaveMuc)
                MucOptions.Error.NON_ANONYMOUS -> showSnackbar(
                    R.string.group_chat_will_make_your_jabber_id_public,
                    R.string.join,
                    acceptJoin
                )
                else -> hideSnackbar()
            }
        } else if (account.hasPendingPgpIntent(conversation)) {
            showSnackbar(R.string.openpgp_messages_found, R.string.decrypt, clickToDecryptListener)
        } else if (connection != null
            && connection.features.blocking()
            && conversation.countMessages() != 0
            && !conversation.isBlocked
            && conversation.isWithStranger
        ) {
            showSnackbar(R.string.received_message_from_stranger, R.string.block, mBlockClickListener)
        } else {
            hideSnackbar()
        }
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

    fun refresh(notifyConversationRead: Boolean) {
        synchronized(this.messageList) {
            if (this.conversation != null) {
                conversation!!.populateWithMessages(this.messageList)
                updateSnackBar(conversation!!)
                updateStatusMessages()
                if (conversation!!.getReceivedMessagesCountSinceUuid(lastMessageUuid) != 0) {
                    binding!!.unreadCountCustomView.visibility = View.VISIBLE
                    binding!!.unreadCountCustomView.setUnreadCount(
                        conversation!!.getReceivedMessagesCountSinceUuid(
                            lastMessageUuid
                        )
                    )
                }
                this.messageListAdapter.notifyDataSetChanged()
                updateChatMsgHint()
                if (notifyConversationRead && activity != null) {
                    binding!!.messagesView.post { this.fireReadEvent() }
                }
                updateSendButton()
                updateEditablity()
                activity!!.invalidateOptionsMenu()
            }
        }
    }

    protected fun messageSent() {
        mSendingPgpMessage.set(false)
        this.binding!!.textinput.setText("")
        if (conversation!!.setCorrectingMessage(null)) {
            this.binding!!.textinput.append(conversation!!.draftMessage)
            conversation!!.draftMessage = null
        }
        storeNextMessage()
        updateChatMsgHint()
        val p = PreferenceManager.getDefaultSharedPreferences(activity)
        val prefScrollToBottom =
            p.getBoolean("scroll_to_bottom", activity!!.resources.getBoolean(R.bool.scroll_to_bottom))
        if (prefScrollToBottom || scrolledToBottom()) {
            Handler().post {
                val size = messageList.size
                this.binding!!.messagesView.setSelection(size - 1)
            }
        }
    }

    fun storeNextMessage(msg: String = this.binding!!.textinput.text!!.toString()): Boolean {
        val participating =
            conversation!!.mode == Conversational.MODE_SINGLE || conversation!!.mucOptions.participating()
        if (this.conversation!!.status != Conversation.STATUS_ARCHIVED && participating && this.conversation!!.setNextMessage(
                msg
            )
        ) {
            this.activity!!.xmppConnectionService.updateConversation(this.conversation)
            return true
        }
        return false
    }

    fun doneSendingPgpMessage() {
        mSendingPgpMessage.set(false)
    }

    fun getMaxHttpUploadSize(conversation: Conversation): Long {
        val connection = conversation.account.xmppConnection
        return connection?.features?.maxHttpUploadSize ?: -1
    }

    fun updateEditablity() {
        val canWrite =
            this.conversation!!.mode == Conversation.MODE_SINGLE || this.conversation!!.mucOptions.participating() || this.conversation!!.nextCounterpart != null
        this.binding!!.textinput.isFocusable = canWrite
        this.binding!!.textinput.isFocusableInTouchMode = canWrite
        this.binding!!.textSendButton.isEnabled = canWrite
        this.binding!!.textinput.isCursorVisible = canWrite
        this.binding!!.textinput.isEnabled = canWrite
    }

    fun updateSendButton() {
        val hasAttachments = mediaPreviewAdapter != null && mediaPreviewAdapter!!.hasAttachments()
        val useSendButtonToIndicateStatus = PreferenceManager.getDefaultSharedPreferences(getActivity())
            .getBoolean("send_button_status", resources.getBoolean(R.bool.send_button_status))
        val c = this.conversation
        val status: Presence.Status
        val text = if (this.binding!!.textinput == null) "" else this.binding!!.textinput.text!!.toString()
        val action: SendButtonAction
        if (hasAttachments) {
            action = SendButtonAction.TEXT
        } else {
            action = SendButtonTool.getAction(getActivity(), c!!, text)
        }
        if (useSendButtonToIndicateStatus && c!!.account.status == Account.State.ONLINE) {
            if (activity!!.xmppConnectionService != null && activity!!.xmppConnectionService.messageArchiveService.isCatchingUp(
                    c
                )
            ) {
                status = Presence.Status.OFFLINE
            } else if (c.mode == Conversation.MODE_SINGLE) {
                status = c.contact.shownStatus
            } else {
                status = if (c.mucOptions.online()) Presence.Status.ONLINE else Presence.Status.OFFLINE
            }
        } else {
            status = Presence.Status.OFFLINE
        }
        this.binding!!.textSendButton.tag = action
        this.binding!!.textSendButton.setImageResource(
            SendButtonTool.getSendButtonImageResource(
                getActivity(),
                action,
                status
            )
        )
    }

    protected fun updateDateSeparators() {
        synchronized(this.messageList) {
            DateSeparator.addAll(this.messageList)
        }
    }

    protected fun updateStatusMessages() {
        updateDateSeparators()
        synchronized(this.messageList) {
            if (showLoadMoreMessages(conversation)) {
                this.messageList.add(0, Message.createLoadMoreMessage(conversation))
            }
            if (conversation!!.mode == Conversation.MODE_SINGLE) {
                val state = conversation!!.incomingChatState
                when (state) {
                    ChatState.COMPOSING -> this.messageList.add(
                        Message.createStatusMessage(
                            conversation,
                            getString(R.string.contact_is_typing, conversation!!.name)
                        )
                    )
                    ChatState.PAUSED -> this.messageList.add(
                        Message.createStatusMessage(
                            conversation,
                            getString(R.string.contact_has_stopped_typing, conversation!!.name)
                        )
                    )
                    else -> for (i in this.messageList.indices.reversed()) {
                        val message = this.messageList[i]
                        if (message.type != Message.TYPE_STATUS) {
                            if (message.status == Message.STATUS_RECEIVED) {
                                return
                            } else {
                                if (message.status == Message.STATUS_SEND_DISPLAYED) {
                                    this.messageList.add(
                                        i + 1,
                                        Message.createStatusMessage(
                                            conversation,
                                            getString(R.string.contact_has_read_up_to_this_point, conversation!!.name)
                                        )
                                    )
                                    return
                                }
                            }
                        }
                    }
                }
            } else {
                val mucOptions = conversation!!.mucOptions
                val allUsers = mucOptions.users
                val addedMarkers = HashSet<ReadByMarker>()
                var state = ChatState.COMPOSING
                var users: List<MucOptions.User> = conversation!!.mucOptions.getUsersWithChatState(state, 5)
                if (users.size == 0) {
                    state = ChatState.PAUSED
                    users = conversation!!.mucOptions.getUsersWithChatState(state, 5)
                }
                if (mucOptions.isPrivateAndNonAnonymous) {
                    for (i in this.messageList.indices.reversed()) {
                        val markersForMessage = messageList[i].readByMarkers
                        val shownMarkers = ArrayList<MucOptions.User>()
                        for (marker in markersForMessage) {
                            if (!ReadByMarker.contains(marker, addedMarkers)) {
                                addedMarkers.add(marker) //may be put outside this condition. set should do dedup anyway
                                val user = mucOptions.findUser(marker)
                                if (user != null && !users.contains(user)) {
                                    shownMarkers.add(user)
                                }
                            }
                        }
                        val markerForSender = ReadByMarker.from(messageList[i])
                        val statusMessage: Message?
                        val size = shownMarkers.size
                        if (size > 1) {
                            val body: String
                            if (size <= 4) {
                                body = getString(
                                    R.string.contacts_have_read_up_to_this_point,
                                    UIHelper.concatNames(shownMarkers)
                                )
                            } else if (ReadByMarker.allUsersRepresented(allUsers, markersForMessage, markerForSender)) {
                                body = getString(R.string.everyone_has_read_up_to_this_point)
                            } else {
                                body = getString(
                                    R.string.contacts_and_n_more_have_read_up_to_this_point,
                                    UIHelper.concatNames(shownMarkers, 3),
                                    size - 3
                                )
                            }
                            statusMessage = Message.createStatusMessage(conversation, body)
                            statusMessage!!.counterparts = shownMarkers
                        } else if (size == 1) {
                            statusMessage = Message.createStatusMessage(
                                conversation,
                                getString(
                                    R.string.contact_has_read_up_to_this_point,
                                    UIHelper.getDisplayName(shownMarkers[0])
                                )
                            )
                            statusMessage!!.counterpart = shownMarkers[0].fullJid
                            statusMessage.trueCounterpart = shownMarkers[0].realJid
                        } else {
                            statusMessage = null
                        }
                        if (statusMessage != null) {
                            this.messageList.add(i + 1, statusMessage)
                        }
                        addedMarkers.add(markerForSender)
                        if (ReadByMarker.allUsersRepresented(allUsers, addedMarkers)) {
                            break
                        }
                    }
                }
                if (users.size > 0) {
                    val statusMessage: Message
                    if (users.size == 1) {
                        val user = users[0]
                        val id =
                            if (state == ChatState.COMPOSING) R.string.contact_is_typing else R.string.contact_has_stopped_typing
                        statusMessage =
                            Message.createStatusMessage(conversation, getString(id, UIHelper.getDisplayName(user)))
                        statusMessage.trueCounterpart = user.realJid
                        statusMessage.counterpart = user.fullJid
                    } else {
                        val id =
                            if (state == ChatState.COMPOSING) R.string.contacts_are_typing else R.string.contacts_have_stopped_typing
                        statusMessage =
                            Message.createStatusMessage(conversation, getString(id, UIHelper.concatNames(users)))
                        statusMessage.counterparts = users
                    }
                    this.messageList.add(statusMessage)
                }
                else ""
            }
        }
    }

    fun stopScrolling() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        binding!!.messagesView.dispatchTouchEvent(cancel)
    }

    fun showLoadMoreMessages(c: Conversation?): Boolean {
        if (activity == null || activity!!.xmppConnectionService == null) {
            return false
        }
        val mam = hasMamSupport(c!!) && !c.contact.isBlocked
        val service = activity!!.xmppConnectionService.messageArchiveService
        return mam && (c.lastClearHistory.timestamp != 0L || c.countMessages() == 0 && c.messagesLoaded.get() && c.hasMessagesLeftOnServer() && !service.queryInProgress(
            c
        ))
    }

    fun hasMamSupport(c: Conversation): Boolean =
        if (c.mode == Conversation.MODE_SINGLE) {
            val connection = c.account.xmppConnection
            connection != null && connection.features.mam()
        } else {
            c.mucOptions.mamSupport()
        }

    @JvmOverloads
    protected fun showSnackbar(
        message: Int,
        action: Int,
        clickListener: OnClickListener?,
        longClickListener: View.OnLongClickListener? = null
    ) {
        this.binding!!.snackbar.visibility = View.VISIBLE
        this.binding!!.snackbar.setOnClickListener(null)
        this.binding!!.snackbarMessage.setText(message)
        this.binding!!.snackbarMessage.setOnClickListener(null)
        this.binding!!.snackbarAction.visibility = if (clickListener == null) View.GONE else View.VISIBLE
        if (action != 0) {
            this.binding!!.snackbarAction.setText(action)
        }
        this.binding!!.snackbarAction.setOnClickListener(clickListener)
        this.binding!!.snackbarAction.setOnLongClickListener(longClickListener)
    }

    fun hideSnackbar() {
        this.binding!!.snackbar.visibility = View.GONE
    }

    fun sendMessage(message: Message) {
        activity!!.xmppConnectionService.sendMessage(message)
        messageSent()
    }

    fun sendPgpMessage(message: Message) {
        val xmppService = activity!!.xmppConnectionService
        val contact = message.conversation.contact
        if (!activity!!.hasPgp()) {
            activity!!.showInstallPgpDialog()
            return
        }
        if (conversation!!.account.pgpSignature == null) {
            activity!!.announcePgp(conversation!!.account, conversation, null, activity!!.onOpenPGPKeyPublished)
            return
        }
        if (!mSendingPgpMessage.compareAndSet(false, true)) {
            Log.d(Config.LOGTAG, "sending pgp message already in progress")
        }
        if (conversation!!.mode == Conversation.MODE_SINGLE) {
            if (contact.pgpKeyId != 0L) {
                xmppService.pgpEngine!!.hasKey(contact,
                    object : UiCallback<Contact> {

                        override fun userInputRequried(pi: PendingIntent, contact: Contact) {
                            startPendingIntent(pi, REQUEST_ENCRYPT_MESSAGE)
                        }

                        override fun success(contact: Contact) {
                            encryptTextMessage(message)
                        }

                        override fun error(error: Int, contact: Contact) {
                            activity!!.runOnUiThread {
                                Toast.makeText(
                                    activity,
                                    R.string.unable_to_connect_to_keychain,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            mSendingPgpMessage.set(false)
                        }
                    })

            } else {
                showNoPGPKeyDialog(false, DialogInterface.OnClickListener { _, _ ->
                    conversation!!.nextEncryption = Message.ENCRYPTION_NONE
                    xmppService.updateConversation(conversation)
                    message.encryption = Message.ENCRYPTION_NONE
                    xmppService.sendMessage(message)
                    messageSent()
                })
            }
        } else {
            if (conversation!!.mucOptions.pgpKeysInUse()) {
                if (!conversation!!.mucOptions.everybodyHasKeys()) {
                    val warning = Toast
                        .makeText(
                            getActivity(),
                            R.string.missing_public_keys,
                            Toast.LENGTH_LONG
                        )
                    warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
                    warning.show()
                }
                encryptTextMessage(message)
            } else {
                showNoPGPKeyDialog(true, DialogInterface.OnClickListener { _, _ ->
                    conversation!!.nextEncryption = Message.ENCRYPTION_NONE
                    message.encryption = Message.ENCRYPTION_NONE
                    xmppService.updateConversation(conversation)
                    xmppService.sendMessage(message)
                    messageSent()
                })
            }
        }
    }

    fun encryptTextMessage(message: Message) {
        activity!!.xmppConnectionService.pgpEngine!!.encrypt(message,
            object : UiCallback<Message> {

                override fun userInputRequried(pi: PendingIntent, message: Message) {
                    startPendingIntent(pi, REQUEST_SEND_MESSAGE)
                }

                override fun success(message: Message) {
                    //TODO the following two call can be made before the callback
                    getActivity().runOnUiThread { messageSent() }
                }

                override fun error(error: Int, message: Message) {
                    getActivity().runOnUiThread {
                        doneSendingPgpMessage()
                        Toast.makeText(
                            getActivity(),
                            if (error == 0) R.string.unable_to_connect_to_keychain else error,
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                }
            })
    }

    fun showNoPGPKeyDialog(plural: Boolean, listener: DialogInterface.OnClickListener) {
        val builder = AlertDialog.Builder(getActivity())
        builder.setIconAttribute(android.R.attr.alertDialogIcon)
        if (plural) {
            builder.setTitle(getString(R.string.no_pgp_keys))
            builder.setMessage(getText(R.string.contacts_have_no_pgp_keys))
        } else {
            builder.setTitle(getString(R.string.no_pgp_key))
            builder.setMessage(getText(R.string.contact_has_no_pgp_key))
        }
        builder.setNegativeButton(getString(R.string.cancel), null)
        builder.setPositiveButton(getString(R.string.send_unencrypted), listener)
        builder.create().show()
    }

    fun appendText(text: String?, doNotAppend: Boolean) {
        var text: String? = text ?: return
        val editable = this.binding!!.textinput.text
        val previous = editable?.toString() ?: ""
        if (doNotAppend && !TextUtils.isEmpty(previous)) {
            Toast.makeText(getActivity(), R.string.already_drafting_message, Toast.LENGTH_LONG).show()
            return
        }
        if (UIHelper.isLastLineQuote(previous)) {
            text = '\n' + text!!
        } else if (previous.length != 0 && !Character.isWhitespace(previous[previous.length - 1])) {
            text = " " + text!!
        }
        this.binding!!.textinput.append(text)
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

    fun startPendingIntent(pendingIntent: PendingIntent, requestCode: Int) {
        try {
            getActivity().startIntentSenderForResult(pendingIntent.intentSender, requestCode, null, 0, 0, 0)
        } catch (ignored: SendIntentException) {
        }

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

    fun findAndReInitByUuidOrArchive(uuid: String): Boolean {
        val conversation = activity!!.xmppConnectionService.findConversationByUuid(uuid)
        if (conversation == null) {
            clearPending()
            activity!!.onConversationArchived(null!!)
            return false
        }
        reInit(conversation)
        val scrollState = pendingScrollState.pop()
        val lastMessageUuid = pendingLastMessageUuid.pop()
        val attachments = pendingMediaPreviews.pop()
        if (scrollState != null) {
            setScrollPosition(scrollState, lastMessageUuid)
        }
        if (attachments != null && attachments.size > 0) {
            Log.d(Config.LOGTAG, "had attachments on restore")
            mediaPreviewAdapter!!.addMediaPreviews(attachments)
            toggleInputMethod()
        }
        return true
    }

    fun clearPending() {
        if (postponedActivityResult.clear()) {
            Log.e(Config.LOGTAG, "cleared pending intent with unhandled result left")
        }
        if (pendingScrollState.clear()) {
            Log.e(Config.LOGTAG, "cleared scroll state")
        }
        if (pendingTakePhotoUri.clear()) {
            Log.e(Config.LOGTAG, "cleared pending photo uri")
        }
        if (pendingConversationsUuid.clear()) {
            Log.e(Config.LOGTAG, "cleared pending conversations uuid")
        }
        if (pendingMediaPreviews.clear()) {
            Log.e(Config.LOGTAG, "cleared pending media previews")
        }
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
            fragment?.startDownloadable(message)
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