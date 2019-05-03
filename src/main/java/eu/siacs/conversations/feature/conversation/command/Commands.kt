package eu.siacs.conversations.feature.conversation.command

import android.Manifest
import android.content.Intent
import android.content.res.Resources
import android.databinding.DataBindingUtil
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.annotation.StringRes
import android.view.*
import android.widget.AdapterView
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.QuickConversationsService
import eu.siacs.conversations.ui.*
import eu.siacs.conversations.ui.XmppActivity.REQUEST_INVITE_TO_CONVERSATION
import eu.siacs.conversations.ui.adapter.MediaPreviewAdapter
import eu.siacs.conversations.ui.adapter.MessageAdapter
import eu.siacs.conversations.ui.util.*
import eu.siacs.conversations.utils.AccountUtils
import eu.siacs.conversations.utils.PermissionUtils
import eu.siacs.conversations.utils.QuickLoader
import eu.siacs.conversations.utils.StylingHelper
import eu.siacs.conversations.xmpp.chatstate.ChatState
import io.aakit.scope.ActivityScope
import timber.log.Timber
import java.util.*
import javax.inject.Inject


@ActivityScope
class OnCreateOptionsMenu @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) {
    operator fun invoke(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_conversation, menu)
        val menuMucDetails = menu.findItem(R.id.action_muc_details)
        val menuContactDetails = menu.findItem(R.id.action_contact_details)
        val menuInviteContact = menu.findItem(R.id.action_invite)
        val menuMute = menu.findItem(R.id.action_mute)
        val menuUnmute = menu.findItem(R.id.action_unmute)
        val conversation = fragment.conversation

        if (conversation != null) {
            if (conversation.mode == Conversation.MODE_MULTI) {
                menuContactDetails.isVisible = false
                menuInviteContact.isVisible = conversation.mucOptions.canInvite()
                menuMucDetails.setTitle(if (conversation.mucOptions.isPrivateAndNonAnonymous) R.string.action_muc_details else R.string.channel_details)
            } else {
                menuContactDetails.isVisible = !conversation.withSelf()
                menuMucDetails.isVisible = false
                val service = activity.xmppConnectionService
                menuInviteContact.isVisible =
                    service?.findConferenceServer(conversation.account) != null
            }
            if (conversation.isMuted) {
                menuMute.isVisible = false
            } else {
                menuUnmute.isVisible = false
            }
            ConversationMenuConfigurator.configureAttachmentMenu(conversation, menu)
            ConversationMenuConfigurator.configureEncryptionMenu(conversation, menu)
        }
//        super.onCreateOptionsMenu(menu, menuInflater)
    }
}

@ActivityScope
class OnCreateView @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) {
    operator fun invoke(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): View? {
        val binding: FragmentConversationBinding =
            DataBindingUtil.inflate(inflater, R.layout.fragment_conversation, container, false)
        binding.root.setOnClickListener(null) //TODO why the fuck did we do this?

        binding.textinput.addTextChangedListener(StylingHelper.MessageEditorStyler(binding.textinput))

        binding.textinput.setOnEditorActionListener(fragment.mEditorActionListener)
        binding.textinput.setRichContentListener(
            arrayOf("image/*"),
            fragment.mEditorContentListener
        )

        binding.textSendButton.setOnClickListener(fragment.mSendButtonListener)

        binding.scrollToBottomButton.setOnClickListener(fragment.mScrollButtonListener)
        binding.messagesView.setOnScrollListener(fragment.mOnScrollListener)
        binding.messagesView.transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
        val mediaPreviewAdapter = MediaPreviewAdapter(fragment)
        binding.mediaPreview.adapter = mediaPreviewAdapter
        val messageListAdapter = MessageAdapter(activity as XmppActivity, fragment.messageList)
        messageListAdapter.setOnContactPictureClicked(fragment)
        messageListAdapter.setOnContactPictureLongClicked(fragment)
        messageListAdapter.setOnQuoteListener { fragment.quoteText(it) }
        binding.messagesView.adapter = messageListAdapter

        fragment.binding = binding
        fragment.mediaPreviewAdapter = mediaPreviewAdapter
        fragment.messageListAdapter = messageListAdapter

        fragment.registerForContextMenu(binding.messagesView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.textinput.customInsertionActionModeCallback =
                EditMessageActionModeCallback(binding.textinput)
        }

        return binding.root
    }
}

@ActivityScope
class OnCreateContextMenu @Inject constructor(
    private val fragment: ConversationFragment,
    private val populateContextMenu: PopulateContextMenu
) {
    operator fun invoke(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo) {
        synchronized(fragment.messageList) {
            //            super.onCreateContextMenu(menu, v, menuInfo)
            val acmi = menuInfo as AdapterView.AdapterContextMenuInfo
            fragment.selectedMessage = fragment.messageList[acmi.position]
            populateContextMenu(menu)
        }
    }
}

@ActivityScope
class OnContextItemSelected @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val correctMessage: CorrectMessage,
    private val quoteMessage: QuoteMessage,
    private val resendMessage: ResendMessage,
    private val startDownloadable: StartDownloadable,
    private val cancelTransmission: CancelTransmission,
    private val retryDecryption: RetryDecryption,
    private val deleteFile: DeleteFile,
    private val showErrorMessage: ShowErrorMessage,
    private val openWith: OpenWith
) {
    operator fun invoke(item: MenuItem): Boolean =
        fragment.selectedMessage?.let { selectedMessage ->
            when (item.itemId) {
                R.id.share_with -> ShareUtil.share(activity, selectedMessage)
                R.id.correct_message -> correctMessage(selectedMessage)
                R.id.copy_message -> ShareUtil.copyToClipboard(activity, selectedMessage)
                R.id.copy_link -> ShareUtil.copyLinkToClipboard(activity, selectedMessage)
                R.id.quote_message -> quoteMessage(selectedMessage)
                R.id.send_again -> resendMessage(selectedMessage)
                R.id.copy_url -> ShareUtil.copyUrlToClipboard(activity, selectedMessage)
                R.id.download_file -> startDownloadable(selectedMessage)
                R.id.cancel_transmission -> cancelTransmission(selectedMessage)
                R.id.retry_decryption -> retryDecryption(selectedMessage)
                R.id.delete_file -> deleteFile(selectedMessage)
                R.id.show_error_message -> showErrorMessage(selectedMessage)
                R.id.open_with -> openWith(selectedMessage)
                else -> null
            }
        } != null // return super.onContextItemSelected(item)
}

@ActivityScope
class OnOptionsItemSelected @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val handleEncryptionSelection: HandleEncryptionSelection,
    private val handleAttachmentSelection: HandleAttachmentSelection,
    private val clearHistoryDialog: ClearHistoryDialog,
    private val muteConversationDialog: MuteConversationDialog,
    private val unmuteConversation: UnmuteConversation
) {
    operator fun invoke(item: MenuItem): Boolean = fragment.conversation
        ?.takeUnless { MenuDoubleTabUtil.shouldIgnoreTap() }
        ?.let { conversation -> handle(conversation, item) } != null
    //        return super.onOptionsItemSelected(item)

    private fun handle(
        conversation: Conversation,
        item: MenuItem
    ): Any? = when (item.itemId) {
        R.id.encryption_choice_axolotl,
        R.id.encryption_choice_pgp,
        R.id.encryption_choice_none -> handleEncryptionSelection(item)

        R.id.attach_choose_picture,
        R.id.attach_take_picture,
        R.id.attach_record_video,
        R.id.attach_choose_file,
        R.id.attach_record_voice,
        R.id.attach_location -> handleAttachmentSelection(item)

        R.id.action_archive -> activity.xmppConnectionService.archiveConversation(
            conversation
        )

        R.id.action_contact_details -> activity.switchToContactDetails(conversation.contact)

        R.id.action_muc_details -> {
            val intent = Intent(activity, ConferenceDetailsActivity::class.java)
            intent.action = ConferenceDetailsActivity.ACTION_VIEW_MUC
            intent.putExtra("uuid", conversation.uuid)
            fragment.startActivity(intent)
        }

        R.id.action_invite -> fragment.startActivityForResult(
            ChooseContactActivity.create(activity, conversation),
            REQUEST_INVITE_TO_CONVERSATION
        )

        R.id.action_clear_history -> clearHistoryDialog(conversation)

        R.id.action_mute -> muteConversationDialog(conversation)

        R.id.action_unmute -> unmuteConversation(conversation)

        R.id.action_block,
        R.id.action_unblock -> BlockContactDialog.show(activity, conversation)

        else -> null
    }
}


@ActivityScope
class OnRequestPermissionsResult @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val startDownloadable: StartDownloadable,
    private val attachEditorContentToConversation: AttachEditorContentToConversation,
    private val commitAttachments: CommitAttachments,
    private val attachFile: AttachFile
) {
    operator fun invoke(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty()) {
            if (PermissionUtils.allGranted(grantResults)) when (requestCode) {
                ConversationFragment.REQUEST_START_DOWNLOAD -> fragment.mPendingDownloadableMessage?.let(
                    startDownloadable
                )
                ConversationFragment.REQUEST_ADD_EDITOR_CONTENT -> fragment.mPendingEditorContent?.let(
                    attachEditorContentToConversation
                )
                ConversationFragment.REQUEST_COMMIT_ATTACHMENTS -> commitAttachments()
                else -> attachFile(requestCode)
            } else {
                @StringRes val res: Int =
                    when (PermissionUtils.getFirstDenied(grantResults, permissions)) {
                        Manifest.permission.RECORD_AUDIO -> R.string.no_microphone_permission
                        Manifest.permission.CAMERA -> R.string.no_camera_permission
                        else -> R.string.no_storage_permission
                    }
                Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()
            }
        }
        if (PermissionUtils.writeGranted(grantResults, permissions)) {
            activity.xmppConnectionService?.restartFileObserver()
            fragment.refresh()
        }
    }
}

@ActivityScope
class onResume @Inject constructor(
    private val binding: FragmentConversationBinding,
    private val fireReadEvent: FireReadEvent
) {
    operator fun invoke() {
//        super.onResume()
        binding.messagesView.post { fireReadEvent() }
    }
}

@ActivityScope
class OnSaveInstanceState @Inject constructor(
    private val fragment: ConversationFragment
) {
    operator fun invoke(outState: Bundle) {
//        super.onSaveInstanceState(outState)
        fragment.conversation?.let { conversation ->
            outState.putString(ConversationFragment.STATE_CONVERSATION_UUID, conversation.uuid)
            outState.putString(ConversationFragment.STATE_LAST_MESSAGE_UUID, fragment.lastMessageUuid)
            val uri = fragment.pendingTakePhotoUri.peek()
            if (uri != null) {
                outState.putString(ConversationFragment.STATE_PHOTO_URI, uri.toString())
            }
            val scrollState = fragment.scrollPosition
            if (scrollState != null) {
                outState.putParcelable(ConversationFragment.STATE_SCROLL_POSITION, scrollState)
            }
            val attachments = if (fragment.mediaPreviewAdapter == null) ArrayList() else fragment.mediaPreviewAdapter!!.attachments
            if (attachments.size > 0) {
                outState.putParcelableArrayList(
                    ConversationFragment.STATE_MEDIA_PREVIEWS,
                    attachments
                )
            }
        }
    }
}

@ActivityScope
class OnActivityCreated @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) {
    operator fun invoke(savedInstanceState: Bundle?) {
//        super.onActivityCreated(savedInstanceState)
        if (savedInstanceState == null) {
            return
        }
        val uuid = savedInstanceState.getString(ConversationFragment.STATE_CONVERSATION_UUID)
        val attachments =
            savedInstanceState.getParcelableArrayList<Attachment>(ConversationFragment.STATE_MEDIA_PREVIEWS)
        fragment.pendingLastMessageUuid.push(
            savedInstanceState.getString(
                ConversationFragment.STATE_LAST_MESSAGE_UUID,
                null
            )
        )
        if (uuid != null) {
            QuickLoader.set(uuid)
            fragment.pendingConversationsUuid.push(uuid)
            if (attachments != null && attachments.size > 0) {
                fragment.pendingMediaPreviews.push(attachments)
            }
            val takePhotoUri = savedInstanceState.getString(ConversationFragment.STATE_PHOTO_URI)
            if (takePhotoUri != null) {
                fragment.pendingTakePhotoUri.push(Uri.parse(takePhotoUri))
            }
            fragment.pendingScrollState.push(savedInstanceState.getParcelable(ConversationFragment.STATE_SCROLL_POSITION))
        }
    }
}

@ActivityScope
class OnStart @Inject constructor(
        private val activity: ConversationsActivity,
        private val fragment: ConversationFragment
    ) {

    operator fun invoke() {
//        super.onStart()
        val conversation = fragment.conversation ?: return
        if (fragment.reInitRequiredOnStart) fragment.run {
            val extras = pendingExtras.pop()
            reInit(conversation, extras != null)
            if (extras != null) {
                processExtras(extras)
            }
        } else if (activity.xmppConnectionService != null) fragment.run {
            val uuid: String? = pendingConversationsUuid.pop()
            Timber.e("ConversationFragment.onStart() - activity was bound but no conversation loaded. uuid=${uuid!!}")
            if (uuid != null) {
                findAndReInitByUuidOrArchive(uuid)
            }
        }
    }
}

@ActivityScope
class OnStop @Inject constructor(
        private val activity: ConversationsActivity,
        private val fragment: ConversationFragment,
        private val binding: FragmentConversationBinding
    ) {

    operator fun invoke() {
//        super.onStop()
        val messageListAdapter = fragment.messageListAdapter
        messageListAdapter.unregisterListenerInAudioPlayer()
        if (!activity.isChangingConfigurations) {
            SoftKeyboardUtils.hideSoftKeyboard(activity)
            messageListAdapter.stopAudioPlayer()
        }
        val conversation = fragment.conversation
        if (conversation != null) {
            val msg = binding.textinput.text!!.toString()
            fragment.storeNextMessage(msg)
            fragment.updateChatState(conversation, msg)
            activity.xmppConnectionService!!.notificationService.setOpenConversation(null)
        }
        fragment.reInitRequiredOnStart = true
    }
}

@ActivityScope
class OnEnterPressed @Inject constructor(
    private val activity: ConversationsActivity,
    private val resources: Resources,
    private val sendMessage: SendMessage
) {
    operator fun invoke(): Boolean {
        val p = PreferenceManager.getDefaultSharedPreferences(activity)
        val enterIsSend = p.getBoolean("enter_is_send", resources.getBoolean(R.bool.enter_is_send))
        return if (enterIsSend) {
            sendMessage()
            true
        } else {
            false
        }
    }
}

@ActivityScope
class OnTypingStarted @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val updateSendButton: UpdateSendButton
) {
    operator fun invoke() {
        val conversation = fragment.conversation!!
        val service = (activity.xmppConnectionService) ?: return
        val status = conversation.account.status
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(ChatState.COMPOSING)) {
            service.sendChatState(conversation)
        }
        updateSendButton()
    }
}

@ActivityScope
class OnTypingStopped @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) {
    operator fun invoke() {
        val service = (activity.xmppConnectionService) ?: return
        val conversation = fragment.conversation!!
        val status = conversation.account.status
        if (status == Account.State.ONLINE && conversation!!.setOutgoingChatState(ChatState.PAUSED)) {
            service.sendChatState(conversation)
        }
    }
}

@ActivityScope
class OnTextDeleted @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val storeNextMessage: StoreNextMessage,
    private val updateSendButton: UpdateSendButton
) {
    operator fun invoke() {
        val service = (activity.xmppConnectionService) ?: return
        val conversation = fragment.conversation!!
        val status = conversation.account.status
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(Config.DEFAULT_CHATSTATE)) {
            service.sendChatState(conversation)
        }
        if (storeNextMessage()) {
            activity.onConversationsListItemUpdated()
        }
        updateSendButton()
    }
}

@ActivityScope
class OnTextChanged @Inject constructor(
    private val fragment: ConversationFragment,
    private val updateSendButton: UpdateSendButton
) {
    operator fun invoke() {
        val conversation = fragment.conversation!!
        if (conversation.correctingMessage != null) {
            updateSendButton()
        }
    }
}

@ActivityScope
class OnTabPressed @Inject constructor(
    private val fragment: ConversationFragment
) {
    operator fun invoke(repeated: Boolean): Boolean {
        val conversation = fragment.conversation
        if (conversation == null || conversation.mode == Conversation.MODE_SINGLE) {
            return false
        }
        fragment.run {
            if (repeated) {
                completionIndex++
            } else {
                lastCompletionLength = 0
                completionIndex = 0
                val content = this.binding!!.textinput.text!!.toString()
                lastCompletionCursor = this.binding!!.textinput.selectionEnd
                val start = if (lastCompletionCursor > 0) content.lastIndexOf(
                    " ",
                    lastCompletionCursor - 1
                ) + 1 else 0
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
            completions.sort()
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
    }
}

@ActivityScope
class OnBackendConnected @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val findAndReInitByUuidOrArchive: FindAndReInitByUuidOrArchive,
    private val clearPending: ClearPending,
    private val handleActivityResult: HandleActivityResult
) {
    operator fun invoke() {
        Timber.d("ConversationFragment.onBackendConnected()")
        val uuid = fragment.pendingConversationsUuid.pop()
        val conversation = fragment.conversation
        if (uuid != null) {
            if (!findAndReInitByUuidOrArchive(uuid)) {
                return
            }
        } else {
            if (!activity.xmppConnectionService.isConversationStillOpen(conversation)) {
                clearPending()
                activity.onConversationArchived(conversation!!)
                return
            }
        }
        val activityResult = fragment.postponedActivityResult.pop()
        if (activityResult != null) {
            handleActivityResult(activityResult)
        }
        clearPending()
    }
}

@ActivityScope
class OnContactPictureLongClicked @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) {
    operator fun invoke(v: View, message: Message) {
        val fingerprint: String
        if (message.encryption == Message.ENCRYPTION_PGP || message.encryption == Message.ENCRYPTION_DECRYPTED) {
            fingerprint = "pgp"
        } else {
            fingerprint = message.fingerprint
        }
        val popupMenu = PopupMenu(activity, v)
        val contact = message.contact
        val conversation = fragment.conversation!!
        if (message.status <= Message.STATUS_RECEIVED && (contact == null || !contact.isSelf)) {
            if (message.conversation.mode == Conversation.MODE_MULTI) {
                val cp = message.counterpart
                if (cp == null || cp.isBareJid) {
                    return
                }
                val tcp = message.trueCounterpart
                val userByRealJid =
                    if (tcp != null) conversation.mucOptions.findOrCreateUserByRealJid(
                        tcp,
                        cp
                    ) else null
                val user = userByRealJid ?: conversation.mucOptions.findUserByFullJid(cp)
                popupMenu.inflate(R.menu.muc_details_context)
                val menu = popupMenu.menu
                MucDetailsContextMenuHelper.configureMucDetailsContextMenu(
                    activity,
                    menu,
                    conversation,
                    user
                )
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
                        R.id.action_contact_details -> activity.switchToContactDetails(
                            message.contact,
                            fingerprint
                        )
                        R.id.action_show_qr_code -> activity.showQrCode("xmpp:" + message.contact!!.jid.asBareJid().toEscapedString())
                    }
                    true
                }
            }
        } else {
            popupMenu.inflate(R.menu.account_context)
            val menu = popupMenu.menu
            menu.findItem(R.id.action_manage_accounts).isVisible =
                QuickConversationsService.isConversations()
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_show_qr_code -> activity.showQrCode(conversation.account.shareableUri)
                    R.id.action_account_details -> activity.switchToAccount(
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
}

@ActivityScope
class OnContactPictureClicked @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) {
    operator fun invoke(message: Message) {
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
                                activity,
                                activity.getString(
                                    R.string.user_has_left_conference,
                                    user.resource
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        fragment.highlightInConference(user.resource)
                    } else {
                        Toast.makeText(
                            activity,
                            R.string.you_are_not_participating,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return
            } else {
                if (!message.contact!!.isSelf) {
                    activity.switchToContactDetails(message.contact, fingerprint)
                    return
                }
            }
        }
        activity.switchToAccount(message.conversation.account, fingerprint)
    }
}