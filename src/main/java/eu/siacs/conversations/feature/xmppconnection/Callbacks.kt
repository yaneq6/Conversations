package eu.siacs.conversations.feature.xmppconnection

import android.os.Environment
import android.text.TextUtils
import eu.siacs.conversations.Config
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.di.ServiceScope
import eu.siacs.conversations.generator.IqGenerator
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.services.*
import eu.siacs.conversations.utils.ConversationsFileObserver
import eu.siacs.conversations.xmpp.*
import eu.siacs.conversations.xmpp.OnBindListener
import eu.siacs.conversations.xmpp.OnContactStatusChanged
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager
import eu.siacs.conversations.xmpp.jingle.OnJinglePacketReceived
import eu.siacs.conversations.xmpp.jingle.stanzas.JinglePacket
import eu.siacs.conversations.xmpp.stanzas.IqPacket
import timber.log.Timber
import javax.inject.Inject

@ServiceScope
class DefaultIqHandler @Inject constructor() : OnIqPacketReceived {
    override fun onIqPacketReceived(account: Account, packet: IqPacket) {
        if (packet.getType() != IqPacket.TYPE.RESULT) {
            val error = packet.findChild("error")
            val text = if (error != null) error.findChildContent("text") else null
            if (text != null) {
                Timber.d(account.getJid().asBareJid().toString() + ": received iq error - " + text)
            }
        }
    }
}

@ServiceScope
class OnContactStatusChanged @Inject constructor(
    private val find: Find,
    private val getConversations: GetConversations,
    private val sendUnsentMessages: SendUnsentMessages
) : OnContactStatusChanged {
    override fun onContactStatusChanged(contact: Contact, online: Boolean) {
        val conversation = find(getConversations(), contact)
        if (conversation != null) {
            if (online) {
                if (contact.presences.size() == 1) {
                    sendUnsentMessages(conversation)
                }
            }
        }
    }
}

@ServiceScope
class JingleListener @Inject constructor(
    private val jingleConnectionManager: JingleConnectionManager
) : OnJinglePacketReceived {
    override fun onJinglePacketReceived(account: Account, packet: JinglePacket) {
        jingleConnectionManager.deliverPacket(
            account,
            packet
        )
    }
}

@ServiceScope
class FileObserver @Inject constructor(
    private val markFileDeleted: MarkFileDeleted
) : ConversationsFileObserver(
    Environment.getExternalStorageDirectory().absolutePath
) {
    override fun onEvent(event: Int, path: String) {
        markFileDeleted(path)
    }
}

@ServiceScope
class OnMessageAcknowledgedListener @Inject constructor(
    private val getConversations: GetConversations,
    private val databaseBackend: DatabaseBackend
) : OnMessageAcknowledged {
    override fun onMessageAcknowledged(account: Account, uuid: String?): Boolean {
        for (conversation in getConversations()) {
            if (conversation.account === account) {
                val message = conversation.findUnsentMessageWithUuid(uuid)
                if (message != null) {
                    message.status = Message.STATUS_SEND
                    message.errorMessage = null
                    databaseBackend.updateMessage(message, false)
                    return true
                }
            }
        }
        return false
    }
}

@ServiceScope
class OnBindListener @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val publishDisplayName: PublishDisplayName,
    private val jingleConnectionManager: JingleConnectionManager,
    private val quickConversationsService: QuickConversationsService,
    private val fetchRosterFromServer: FetchRosterFromServer,
    private val fetchBookmarks: FetchBookmarks,
    private val messageArchiveService: MessageArchiveService,
    private val sendIqPacket: SendIqPacket,
    private val iqGenerator: IqGenerator,
    private val sendPresence: SendPresence,
    private val pushManagementService: PushManagementService,
    private val connectMultiModeConversations: ConnectMultiModeConversations,
    private val syncDirtyContacts: SyncDirtyContacts
) : OnBindListener {
    override fun onBind(account: Account) {
        synchronized(service.mInProgressAvatarFetches) {
            val iterator = service.mInProgressAvatarFetches.iterator()
            while (iterator.hasNext()) {
                val KEY = iterator.next()
                if (KEY.startsWith(account.jid.asBareJid().toString() + "_")) {
                    iterator.remove()
                }
            }
        }
        val loggedInSuccessfully =
            account.setOption(Account.OPTION_LOGGED_IN_SUCCESSFULLY, true)
        val gainedFeature = account.setOption(
            Account.OPTION_HTTP_UPLOAD_AVAILABLE,
            account.xmppConnection.features.httpUpload(0)
        )
        if (loggedInSuccessfully || gainedFeature) {
            databaseBackend.updateAccount(account)
        }

        if (loggedInSuccessfully) {
            if (!TextUtils.isEmpty(account.displayName)) {
                Timber.d(account.jid.asBareJid().toString() + ": display name wasn't empty on first log in. publishing")
                publishDisplayName(account)
            }
        }

        account.roster.clearPresences()
        jingleConnectionManager.cancelInTransmission()
        quickConversationsService.considerSyncBackground(false)
        fetchRosterFromServer(account)
        if (!account.xmppConnection.features.bookmarksConversion()) {
            fetchBookmarks(account)
        }
        val flexible = account.xmppConnection.features.flexibleOfflineMessageRetrieval()
        val catchup = messageArchiveService.inCatchup(account)
        if (flexible && catchup && account.xmppConnection.isMamPreferenceAlways) {
            sendIqPacket(
                account,
                iqGenerator.purgeOfflineMessages(),
                OnIqPacketReceived { acc, packet ->
                    if (packet.getType() == IqPacket.TYPE.RESULT) {
                        Timber.d(acc.getJid().asBareJid().toString() + ": successfully purged offline messages")
                    }
                })
        }
        sendPresence(account)
        if (pushManagementService.available(account)) {
            pushManagementService.registerPushTokenOnServer(account)
        }
        connectMultiModeConversations(account)
        syncDirtyContacts(account)
    }
}


@ServiceScope
class StatusListener @Inject constructor(
    private val service: XmppConnectionService,
    private val updateAccountUi: UpdateAccountUi,
    private val quickConversationsService: QuickConversationsService,
    private val databaseBackend: DatabaseBackend,
    private val messageArchiveService: MessageArchiveService,
    private val checkListeners: CheckListeners,
    private val getConversations: GetConversations,
    private val sendUnsentMessages: SendUnsentMessages,
    private val leaveMuc: LeaveMuc,
    private val joinMuc: JoinMuc,
    private val scheduleWakeUpCall: ScheduleWakeUpCall,
    private val resetSendingToWaiting: ResetSendingToWaiting,
    private val isInLowPingTimeoutMode: IsInLowPingTimeoutMode,
    private val reconnectAccount: ReconnectAccount,
    private val notificationService: NotificationService
) : OnStatusChanged {
    override fun onStatusChanged(account: Account) {
        val connection = account.xmppConnection
        updateAccountUi()

        if (account.status == Account.State.ONLINE || account.status.isError) {
            quickConversationsService.signalAccountStateChange()
        }

        if (account.status == Account.State.ONLINE) {
            synchronized(service.mLowPingTimeoutMode) {
                if (service.mLowPingTimeoutMode.remove(account.jid.asBareJid())) {
                    Timber.d(account.jid.asBareJid().toString() + ": leaving low ping timeout mode")
                }
            }
            if (account.setShowErrorNotification(true)) {
                databaseBackend.updateAccount(account)
            }
            messageArchiveService.executePendingQueries(account)
            if (connection != null && connection.features.csi()) {
                if (checkListeners()) {
                    Timber.d(account.jid.asBareJid().toString() + " sending csi//inactive")
                    connection.sendInactive()
                } else {
                    Timber.d(account.jid.asBareJid().toString() + " sending csi//active")
                    connection.sendActive()
                }
            }
            val conversations = getConversations()
            for (conversation in conversations) {
                if (conversation.account === account && !account.pendingConferenceJoins.contains(
                        conversation
                    )
                ) {
                    sendUnsentMessages(conversation)
                }
            }
            for (conversation in account.pendingConferenceLeaves) {
                leaveMuc(conversation)
            }
            account.pendingConferenceLeaves.clear()
            for (conversation in account.pendingConferenceJoins) {
                joinMuc(conversation)
            }
            account.pendingConferenceJoins.clear()
            scheduleWakeUpCall(Config.PING_MAX_INTERVAL, account.uuid.hashCode())
        } else if (account.status == Account.State.OFFLINE || account.status == Account.State.DISABLED) {
            resetSendingToWaiting(account)
            if (account.isEnabled && isInLowPingTimeoutMode(account)) {
                Timber.d(account.jid.asBareJid().toString() + ": went into offline state during low ping mode. reconnecting now")
                reconnectAccount(account, true, false)
            } else {
                val timeToReconnect = service.rng!!.nextInt(10) + 2
                scheduleWakeUpCall(timeToReconnect, account.uuid.hashCode())
            }
        } else if (account.status == Account.State.REGISTRATION_SUCCESSFUL) {
            databaseBackend.updateAccount(account)
            reconnectAccount(account, true, false)
        } else if (account.status != Account.State.CONNECTING && account.status != Account.State.NO_INTERNET) {
            resetSendingToWaiting(account)
            if (connection != null && account.status.isAttemptReconnect) {
                val next = connection.timeToNextAttempt
                val lowPingTimeoutMode = isInLowPingTimeoutMode(account)
                if (next <= 0) {
                    Timber.d(account.jid.asBareJid().toString() + ": error connecting account. reconnecting now. lowPingTimeout=" + java.lang.Boolean.toString(lowPingTimeoutMode))
                    reconnectAccount(account, true, false)
                } else {
                    val attempt = connection.attempt + 1
                    Timber.d(account.jid.asBareJid().toString() + ": error connecting account. try again in " + next + "s for the " + attempt + " time. lowPingTimeout=" + java.lang.Boolean.toString(lowPingTimeoutMode))
                    scheduleWakeUpCall(next, account.uuid.hashCode())
                }
            }
        }
        notificationService.updateErrorNotification()
    }
}
