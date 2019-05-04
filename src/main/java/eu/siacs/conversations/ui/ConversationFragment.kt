package eu.siacs.conversations.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.widget.Toast
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.conversation.callback.*
import eu.siacs.conversations.feature.conversation.command.*
import eu.siacs.conversations.ui.adapter.MediaPreviewAdapter
import eu.siacs.conversations.ui.adapter.MessageAdapter
import eu.siacs.conversations.ui.util.ActivityResult
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.ui.util.PendingItem
import eu.siacs.conversations.ui.util.ScrollState
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
    lateinit var sendMessage: SendMessage
    @Inject
    lateinit var handleActivityResult: HandleActivityResult
    @Inject
    lateinit var toggleInputMethod: ToggleInputMethod
    @Inject
    lateinit var attachFile: AttachFile
    @Inject
    lateinit var fireReadEvent: FireReadEvent
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
    lateinit var startDownloadable: StartDownloadable
    @Inject
    lateinit var storeNextMessage: StoreNextMessage
    @Inject
    lateinit var updateChatMsgHint: UpdateChatMsgHint
    @Inject
    lateinit var updateSendButton: UpdateSendButton
    @Inject
    lateinit var onCreateOptionsMenu: OnCreateOptionsMenu
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

    @Inject
    lateinit var clickToMuc: ClickToMuc
    @Inject
    lateinit var leaveMuc: LeaveMuc
    @Inject
    lateinit var joinMuc: JoinMuc
    @Inject
    lateinit var acceptJoin: AcceptJoin
    @Inject
    lateinit var enterPassword: EnterPassword
    @Inject
    lateinit var onScrollListener: OnScrollListener
    @Inject
    lateinit var editorContentListener: EditorContentListener
    @Inject
    lateinit var enableAccountListener: EnableAccountListener
    @Inject
    lateinit var unblockClickListener: UnblockClickListener
    @Inject
    lateinit var blockClickListener: BlockClickListener
    @Inject
    lateinit var addBackClickListener: AddBackClickListener
    @Inject
    lateinit var longPressBlockListener: LongPressBlockListener
    @Inject
    lateinit var allowPresenceSubscription: AllowPresenceSubscription
    @Inject
    lateinit var clickToDecryptListener: ClickToDecryptListener
    @Inject
    lateinit var editorActionListener: EditorActionListener
    @Inject
    lateinit var scrollButtonListener: ScrollButtonListener
    @Inject
    lateinit var sendButtonListener: SendButtonListener

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