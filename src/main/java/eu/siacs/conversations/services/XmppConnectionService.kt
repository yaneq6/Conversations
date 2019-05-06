package eu.siacs.conversations.services


import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.os.PowerManager.WakeLock
import android.preference.PreferenceManager
import android.provider.ContactsContract
import android.security.KeyChain
import android.support.annotation.BoolRes
import android.support.annotation.IntegerRes
import android.support.v4.app.RemoteInput
import android.support.v4.content.ContextCompat
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import android.util.Pair
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.android.JabberIdContact
import eu.siacs.conversations.crypto.OmemoSetting
import eu.siacs.conversations.crypto.PgpEngine
import eu.siacs.conversations.crypto.axolotl.AxolotlService
import eu.siacs.conversations.entities.*
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.MucOptions.OnRenameListener
import eu.siacs.conversations.generator.AbstractGenerator
import eu.siacs.conversations.generator.IqGenerator
import eu.siacs.conversations.generator.MessageGenerator
import eu.siacs.conversations.generator.PresenceGenerator
import eu.siacs.conversations.http.CustomURLStreamHandlerFactory
import eu.siacs.conversations.http.HttpConnectionManager
import eu.siacs.conversations.http.services.MuclumbusService
import eu.siacs.conversations.parser.AbstractParser
import eu.siacs.conversations.parser.IqParser
import eu.siacs.conversations.parser.MessageParser
import eu.siacs.conversations.parser.PresenceParser
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.ui.ChooseAccountForProfilePictureActivity
import eu.siacs.conversations.ui.SettingsActivity
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.ui.interfaces.OnAvatarPublication
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable
import eu.siacs.conversations.utils.*
import eu.siacs.conversations.xml.Element
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xmpp.*
import eu.siacs.conversations.xmpp.chatstate.ChatState
import eu.siacs.conversations.xmpp.forms.Data
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager
import eu.siacs.conversations.xmpp.jingle.OnJinglePacketReceived
import eu.siacs.conversations.xmpp.jingle.stanzas.JinglePacket
import eu.siacs.conversations.xmpp.mam.MamReference
import eu.siacs.conversations.xmpp.pep.Avatar
import eu.siacs.conversations.xmpp.pep.PublishOptions
import eu.siacs.conversations.xmpp.stanzas.IqPacket
import eu.siacs.conversations.xmpp.stanzas.MessagePacket
import eu.siacs.conversations.xmpp.stanzas.PresencePacket
import me.leolin.shortcutbadger.ShortcutBadger
import org.conscrypt.Conscrypt
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import rocks.xmpp.addr.Jid
import timber.log.Timber
import java.io.File
import java.net.URL
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import java.util.Collections.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class XmppConnectionService : Service() {

    @JvmField
    val restoredFromDatabaseLatch = CountDownLatch(1)
    val mFileAddingExecutor = SerialSingleThreadExecutor("FileAdding")
    val mVideoCompressionExecutor = SerialSingleThreadExecutor("VideoCompression")
    val mDatabaseWriterExecutor = SerialSingleThreadExecutor("DatabaseWriter")
    val mDatabaseReaderExecutor = SerialSingleThreadExecutor("DatabaseReader")
    val mNotificationExecutor = SerialSingleThreadExecutor("NotificationExecutor")
    val mRosterSyncTaskManager = ReplacingTaskManager()
    val mBinder = XmppConnectionBinder()
    val conversations = CopyOnWriteArrayList<Conversation>()
    val iqGenerator = IqGenerator(this)
    val mInProgressAvatarFetches = HashSet<String>()
    val mOmittedPepAvatarFetches = HashSet<String>()
    val mLowPingTimeoutMode = HashSet<Jid>()
    val mContactMergerExecutor = ReplacingSerialSingleThreadExecutor("ContactMerger")

    val fileBackend = FileBackend(this)
    val notificationService = NotificationService(this)
    val shortcutService = ShortcutService(this)
    val mInitialAddressbookSyncCompleted = AtomicBoolean(false)
    val mForceForegroundService = AtomicBoolean(false)
    val mForceDuringOnCreate = AtomicBoolean(false)
    val mMessageParser = MessageParser(this)
    val mPresenceParser = PresenceParser(this)
    val iqParser = IqParser(this)
    val messageGenerator = MessageGenerator(this)

    val presenceGenerator = PresenceGenerator(this)
    val jingleConnectionManager = JingleConnectionManager(this)
    val httpConnectionManager = HttpConnectionManager(this)
    val avatarService = AvatarService(this)
    val messageArchiveService = MessageArchiveService(this)
    val pushManagementService = PushManagementService(this)
    val quickConversationsService = QuickConversationsService(this)

    var muclumbusService: MuclumbusService? = null
    var memorizingTrustManager: MemorizingTrustManager? = null
    var mLastActivity: Long = 0
    var destroyed = false
    var unreadCount = -1
    var accounts: MutableList<Account>? = null


    //Ui callback listeners
    val mOnConversationUpdates = newSetFromMap(WeakHashMap<OnConversationUpdate, Boolean>())
    val mOnShowErrorToasts = newSetFromMap(WeakHashMap<OnShowErrorToast, Boolean>())
    val mOnAccountUpdates = newSetFromMap(WeakHashMap<OnAccountUpdate, Boolean>())
    val mOnCaptchaRequested = newSetFromMap(WeakHashMap<OnCaptchaRequested, Boolean>())
    val mOnRosterUpdates = newSetFromMap(WeakHashMap<OnRosterUpdate, Boolean>())
    val mOnUpdateBlocklist = newSetFromMap(WeakHashMap<OnUpdateBlocklist, Boolean>())
    val mOnMucRosterUpdate = newSetFromMap(WeakHashMap<OnMucRosterUpdate, Boolean>())
    val mOnKeyStatusUpdated = newSetFromMap(WeakHashMap<OnKeyStatusUpdated, Boolean>())

    val LISTENER_LOCK = Any()

    lateinit var databaseBackend: DatabaseBackend

    val mLastExpiryRun = AtomicLong(0)
    var rng: SecureRandom? = null
    val discoCache = LruCache<Pair<String, String>, ServiceDiscoveryResult>(20)

    var pgpServiceConnection: OpenPgpServiceConnection? = null
    var mPgpEngine: PgpEngine? = null
    var wakeLock: WakeLock? = null
    var pm: PowerManager? = null
    var bitmapCache: LruCache<String, Bitmap>? = null
    val mInternalEventReceiver = InternalEventReceiver()
    val mInternalScreenEventReceiver = InternalEventReceiver()

    val mDefaultIqHandler = object : OnIqPacketReceived {
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

    @JvmField
    val onContactStatusChanged = object : OnContactStatusChanged {
        override fun onContactStatusChanged(contact: Contact, online: Boolean) {
            val conversation = find(getConversations(), contact)
            if (conversation != null) {
                if (online) {
                    if (contact.getPresences().size() == 1) {
                        sendUnsentMessages(conversation)
                    }
                }
            }
        }
    }

    val jingleListener = object : OnJinglePacketReceived {
        override fun onJinglePacketReceived(account: Account, packet: JinglePacket) {
            jingleConnectionManager.deliverPacket(
                account,
                packet
            )
        }
    }

    val fileObserver = object : ConversationsFileObserver(
        Environment.getExternalStorageDirectory().absolutePath
    ) {
        override fun onEvent(event: Int, path: String) {
            markFileDeleted(path)
        }
    }

    val mOnMessageAcknowledgedListener = object : OnMessageAcknowledged {
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

    val mOnBindListener = object : OnBindListener {
        override fun onBind(account: Account) {
            synchronized(mInProgressAvatarFetches) {
                val iterator = mInProgressAvatarFetches.iterator()
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
                    Log.d(
                        Config.LOGTAG,
                        account.jid.asBareJid().toString() + ": display name wasn't empty on first log in. publishing"
                    )
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
                            Log.d(
                                Config.LOGTAG,
                                acc.getJid().asBareJid().toString() + ": successfully purged offline messages"
                            )
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


    val statusListener = object : OnStatusChanged {
        override fun onStatusChanged(account: Account) {
            val connection = account.xmppConnection
            updateAccountUi()

            if (account.status == Account.State.ONLINE || account.status.isError) {
                quickConversationsService.signalAccountStateChange()
            }

            if (account.status == Account.State.ONLINE) {
                synchronized(mLowPingTimeoutMode) {
                    if (mLowPingTimeoutMode.remove(account.jid.asBareJid())) {
                        Log.d(
                            Config.LOGTAG,
                            account.jid.asBareJid().toString() + ": leaving low ping timeout mode"
                        )
                    }
                }
                if (account.setShowErrorNotification(true)) {
                    databaseBackend.updateAccount(account)
                }
                messageArchiveService.executePendingQueries(account)
                if (connection != null && connection.features.csi()) {
                    if (checkListeners()) {
                        Log.d(
                            Config.LOGTAG,
                            account.jid.asBareJid().toString() + " sending csi//inactive"
                        )
                        connection.sendInactive()
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            account.jid.asBareJid().toString() + " sending csi//active"
                        )
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
                    Log.d(
                        Config.LOGTAG,
                        account.jid.asBareJid().toString() + ": went into offline state during low ping mode. reconnecting now"
                    )
                    reconnectAccount(account, true, false)
                } else {
                    val timeToReconnect = rng!!.nextInt(10) + 2
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
                        Log.d(
                            Config.LOGTAG,
                            account.jid.asBareJid().toString() + ": error connecting account. reconnecting now. lowPingTimeout=" + java.lang.Boolean.toString(
                                lowPingTimeoutMode
                            )
                        )
                        reconnectAccount(account, true, false)
                    } else {
                        val attempt = connection.attempt + 1
                        Log.d(
                            Config.LOGTAG,
                            account.jid.asBareJid().toString() + ": error connecting account. try again in " + next + "s for the " + attempt + " time. lowPingTimeout=" + java.lang.Boolean.toString(
                                lowPingTimeoutMode
                            )
                        )
                        scheduleWakeUpCall(next, account.uuid.hashCode())
                    }
                }
            }
            notificationService.updateErrorNotification()
        }
    }


    val pgpEngine: PgpEngine?
        get() {
            if (!Config.supportOpenPgp()) {
                return null
            } else if (pgpServiceConnection != null && pgpServiceConnection!!.isBound) {
                if (this.mPgpEngine == null) {
                    this.mPgpEngine = PgpEngine(
                        OpenPgpApi(
                            applicationContext,
                            pgpServiceConnection!!.service
                        ), this
                    )
                }
                return mPgpEngine
            } else {
                return null
            }

        }

    val openPgpApi: OpenPgpApi?
        get() = if (!Config.supportOpenPgp()) {
            null
        } else if (pgpServiceConnection != null && pgpServiceConnection!!.isBound) {
            OpenPgpApi(this, pgpServiceConnection!!.service)
        } else {
            null
        }

    val isDataSaverDisabled: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val connectivityManager =
                    getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                return !connectivityManager.isActiveNetworkMetered || connectivityManager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
            } else {
                return true
            }
        }

    val compressPicturesPreference: String?
        get() = preferences.getString(
            "picture_compression",
            resources.getString(R.string.picture_compression)
        )

    val targetPresence: Presence.Status
        get() = if (dndOnSilentMode() && isPhoneSilenced) {
            Presence.Status.DND
        } else if (awayWhenScreenOff() && !isInteractive) {
            Presence.Status.AWAY
        } else {
            Presence.Status.ONLINE
        }

    val isInteractive: Boolean
        @SuppressLint("NewApi")
        get() {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

                val isScreenOn: Boolean
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    isScreenOn = pm.isScreenOn
                } else {
                    isScreenOn = pm.isInteractive
                }
                return isScreenOn
            } catch (e: RuntimeException) {
                return false
            }

        }

    val isPhoneSilenced: Boolean
        get() {
            val notificationDnd: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val notificationManager = getSystemService(NotificationManager::class.java)
                val filter = notificationManager?.currentInterruptionFilter
                    ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                notificationDnd = filter >= NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                notificationDnd = false
            }
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
            try {
                return if (treatVibrateAsSilent()) {
                    notificationDnd || ringerMode != AudioManager.RINGER_MODE_NORMAL
                } else {
                    notificationDnd || ringerMode == AudioManager.RINGER_MODE_SILENT
                }
            } catch (throwable: Throwable) {
                Timber.d("platform bug in isPhoneSilenced (" + throwable.message + ")")
                return notificationDnd
            }

        }

    val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(applicationContext)

    val automaticMessageDeletionDate: Long
        get() {
            val timeout = getLongPreference(
                SettingsActivity.AUTOMATIC_MESSAGE_DELETION,
                R.integer.automatic_message_deletion
            )
            return if (timeout == 0L) timeout else System.currentTimeMillis() - timeout * 1000
        }

    //we only want to show this when we type a e164 number
    val knownHosts: Collection<String>
        get() {
            val hosts = HashSet<String>()
            for (account in accounts!!) {
                hosts.add(account.server)
                for (contact in account.roster.contacts) {
                    if (contact.showInRoster()) {
                        val server = contact.server
                        if (server != null) {
                            hosts.add(server)
                        }
                    }
                }
            }
            if (Config.QUICKSY_DOMAIN != null) {
                hosts.remove(Config.QUICKSY_DOMAIN)
            }
            if (Config.DOMAIN_LOCK != null) {
                hosts.add(Config.DOMAIN_LOCK)
            }
            if (Config.MAGIC_CREATE_DOMAIN != null) {
                hosts.add(Config.MAGIC_CREATE_DOMAIN)
            }
            return hosts
        }

    val knownConferenceHosts: Collection<String>
        get() {
            val mucServers = HashSet<String>()
            for (account in accounts!!) {
                if (account.xmppConnection != null) {
                    mucServers.addAll(account.xmppConnection.mucServers)
                    for (bookmark in account.bookmarks) {
                        val jid = bookmark.jid
                        val s = jid?.domain
                        if (s != null) {
                            mucServers.add(s)
                        }
                    }
                }
            }
            return mucServers
        }

    fun getRNG() = rng

    fun isInLowPingTimeoutMode(account: Account): Boolean {
        synchronized(mLowPingTimeoutMode) {
            return mLowPingTimeoutMode.contains(account.jid.asBareJid())
        }
    }

    fun startForcingForegroundNotification() {
        mForceForegroundService.set(true)
        toggleForegroundService()
    }

    fun stopForcingForegroundNotification() {
        mForceForegroundService.set(false)
        toggleForegroundService()
    }

    fun areMessagesInitialized(): Boolean {
        return this.restoredFromDatabaseLatch.count == 0L
    }

    fun attachLocationToConversation(
        conversation: Conversation,
        uri: Uri,
        callback: UiCallback<Message>
    ) {
        var encryption = conversation.nextEncryption
        if (encryption == Message.ENCRYPTION_PGP) {
            encryption = Message.ENCRYPTION_DECRYPTED
        }
        val message = Message(conversation, uri.toString(), encryption)
        if (conversation.nextCounterpart != null) {
            message.counterpart = conversation.nextCounterpart
        }
        if (encryption == Message.ENCRYPTION_DECRYPTED) {
            pgpEngine!!.encrypt(message, callback)
        } else {
            sendMessage(message)
            callback.success(message)
        }
    }

    fun attachFileToConversation(
        conversation: Conversation,
        uri: Uri,
        type: String?,
        callback: UiCallback<Message>?
    ) {
        val message: Message
        if (conversation.nextEncryption == Message.ENCRYPTION_PGP) {
            message = Message(conversation, "", Message.ENCRYPTION_DECRYPTED)
        } else {
            message = Message(conversation, "", conversation.nextEncryption)
        }
        message.counterpart = conversation.nextCounterpart
        message.type = Message.TYPE_FILE
        val runnable = AttachFileToConversationRunnable(this, uri, type, message, callback)
        if (runnable.isVideoMessage) {
            mVideoCompressionExecutor.execute(runnable)
        } else {
            mFileAddingExecutor.execute(runnable)
        }
    }

    fun attachImageToConversation(
        conversation: Conversation,
        uri: Uri,
        callback: UiCallback<Message>?
    ) {
        val mimeType = MimeUtils.guessMimeTypeFromUri(this, uri)
        val compressPictures = compressPicturesPreference

        if ("never" == compressPictures
            || "auto" == compressPictures && fileBackend.useImageAsIs(uri)
            || mimeType != null && mimeType.endsWith("/gif")
            || fileBackend.unusualBounds(uri)
        ) {
            Log.d(
                Config.LOGTAG,
                conversation.account.jid.asBareJid().toString() + ": not compressing picture. sending as file"
            )
            attachFileToConversation(conversation, uri, mimeType, callback)
            return
        }
        val message: Message
        if (conversation.nextEncryption == Message.ENCRYPTION_PGP) {
            message = Message(conversation, "", Message.ENCRYPTION_DECRYPTED)
        } else {
            message = Message(conversation, "", conversation.nextEncryption)
        }
        message.counterpart = conversation.nextCounterpart
        message.type = Message.TYPE_IMAGE
        mFileAddingExecutor.execute {
            try {
                fileBackend.copyImageToPrivateStorage(message, uri)
                if (conversation.nextEncryption == Message.ENCRYPTION_PGP) {
                    val pgpEngine = pgpEngine
                    pgpEngine?.encrypt(message, callback)
                        ?: callback?.error(R.string.unable_to_connect_to_keychain, null)
                } else {
                    sendMessage(message)
                    callback!!.success(message)
                }
            } catch (e: FileBackend.FileCopyException) {
                callback!!.error(e.resId, message)
            }
        }
    }

    fun find(bookmark: Bookmark): Conversation? {
        return find(bookmark.account, bookmark.jid)
    }

    fun find(account: Account, jid: Jid?): Conversation? {
        return find(getConversations(), account, jid)
    }

    fun isMuc(account: Account, jid: Jid): Boolean {
        val c = find(account, jid)
        return c != null && c.mode == Conversational.MODE_MULTI
    }

    fun search(term: List<String>, onSearchResultsAvailable: OnSearchResultsAvailable) {
        MessageSearchTask.search(this, term, onSearchResultsAvailable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val needsForegroundService = intent != null && intent.getBooleanExtra(
            EventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE,
            false
        )
        if (needsForegroundService) {
            Log.d(
                Config.LOGTAG,
                "toggle forced foreground service after receiving event (action=$action)"
            )
            toggleForegroundService(true)
        }
        var pushedAccountHash: String? = null
        var interactive = false
        if (action != null) {
            val uuid = intent.getStringExtra("uuid")
            when (action) {
                ConnectivityManager.CONNECTIVITY_ACTION -> if (hasInternetConnection()) {
                    if (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0) {
                        schedulePostConnectivityChange()
                    }
                    if (Config.RESET_ATTEMPT_COUNT_ON_NETWORK_CHANGE) {
                        resetAllAttemptCounts(true, false)
                    }
                }
                Intent.ACTION_SHUTDOWN -> {
                    logoutAndSave(true)
                    return Service.START_NOT_STICKY
                }
                ACTION_CLEAR_NOTIFICATION -> mNotificationExecutor.execute {
                    try {
                        val c = findConversationByUuid(uuid)
                        if (c != null) {
                            notificationService.clear(c)
                        } else {
                            notificationService.clear()
                        }
                        restoredFromDatabaseLatch.await()

                    } catch (e: InterruptedException) {
                        Timber.d("unable to process clear notification")
                    }
                }
                ACTION_DISMISS_ERROR_NOTIFICATIONS -> dismissErrorNotifications()
                ACTION_TRY_AGAIN -> {
                    resetAllAttemptCounts(false, true)
                    interactive = true
                }
                ACTION_REPLY_TO_CONVERSATION -> {
                    RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence("text_reply")
                        ?.takeIf(CharSequence::isNotEmpty)
                        ?.let { body ->
                            mNotificationExecutor.execute {
                                try {
                                    restoredFromDatabaseLatch.await()
                                    val c = findConversationByUuid(uuid)
                                    if (c != null) {
                                        val dismissNotification =
                                            intent.getBooleanExtra("dismiss_notification", false)
                                        directReply(c, body.toString(), dismissNotification)
                                    }
                                } catch (e: InterruptedException) {
                                    Timber.d("unable to process direct reply")
                                }
                            }
                        }
                }
                ACTION_MARK_AS_READ -> mNotificationExecutor.execute {
                    val c = findConversationByUuid(uuid)
                    if (c == null) {
                        Log.d(
                            Config.LOGTAG,
                            "received mark read intent for unknown conversation ($uuid)"
                        )
                        return@execute
                    }
                    try {
                        restoredFromDatabaseLatch.await()
                        sendReadMarker(c, null)
                    } catch (e: InterruptedException) {
                        Log.d(
                            Config.LOGTAG,
                            "unable to process notification read marker for conversation " + c!!.name
                        )
                    }


                }
                ACTION_SNOOZE -> {
                    mNotificationExecutor.execute {
                        val c = findConversationByUuid(uuid)
                        if (c == null) {
                            Log.d(
                                Config.LOGTAG,
                                "received snooze intent for unknown conversation ($uuid)"
                            )
                            return@execute
                        }
                        c!!.setMutedTill(System.currentTimeMillis() + 30 * 60 * 1000)
                        notificationService.clear(c)
                        updateConversation(c)
                    }
                    if (dndOnSilentMode()) {
                        refreshAllPresences()
                    }
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION, NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> if (dndOnSilentMode()) {
                    refreshAllPresences()
                }
                Intent.ACTION_SCREEN_ON -> {
                    deactivateGracePeriod()
                    if (awayWhenScreenOff()) {
                        refreshAllPresences()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> if (awayWhenScreenOff()) {
                    refreshAllPresences()
                }
                ACTION_FCM_TOKEN_REFRESH -> refreshAllFcmTokens()
                ACTION_IDLE_PING -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    scheduleNextIdlePing()
                }
                ACTION_FCM_MESSAGE_RECEIVED -> {
                    pushedAccountHash = intent.getStringExtra("account")
                    Log.d(
                        Config.LOGTAG,
                        "push message arrived in service. account=" + pushedAccountHash!!
                    )
                }
                Intent.ACTION_SEND -> {
                    val uri = intent.data
                    if (uri != null) {
                        Timber.d("received uri permission for $uri")
                    }
                    return Service.START_STICKY
                }
            }
        }
        synchronized(this) {
            WakeLockHelper.acquire(wakeLock)
            var pingNow =
                ConnectivityManager.CONNECTIVITY_ACTION == action || Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0 && ACTION_POST_CONNECTIVITY_CHANGE == action
            val pingCandidates = HashSet<Account>()
            for (account in accounts!!) {
                pingNow = pingNow or processAccountState(
                    account,
                    interactive,
                    "ui" == action,
                    CryptoHelper.getAccountFingerprint(
                        account,
                        PhoneHelper.getAndroidId(this)
                    ) == pushedAccountHash,
                    pingCandidates
                )
            }
            if (pingNow) {
                for (account in pingCandidates) {
                    val lowTimeout = isInLowPingTimeoutMode(account)
                    account.xmppConnection.sendPing()
                    Log.d(
                        Config.LOGTAG,
                        account.jid.asBareJid().toString() + " send ping (action=" + action + ",lowTimeout=" + java.lang.Boolean.toString(
                            lowTimeout
                        ) + ")"
                    )
                    scheduleWakeUpCall(
                        if (lowTimeout) Config.LOW_PING_TIMEOUT else Config.PING_TIMEOUT,
                        account.uuid.hashCode()
                    )
                }
            }
            WakeLockHelper.release(wakeLock)
        }
        if (SystemClock.elapsedRealtime() - mLastExpiryRun.get() >= Config.EXPIRY_INTERVAL) {
            expireOldMessages()
        }
        return Service.START_STICKY
    }

    fun processAccountState(
        account: Account,
        interactive: Boolean,
        isUiAction: Boolean,
        isAccountPushed: Boolean,
        pingCandidates: HashSet<Account>
    ): Boolean {
        var pingNow = false
        if (account.status.isAttemptReconnect) {
            if (!hasInternetConnection()) {
                account.status = Account.State.NO_INTERNET
                statusListener?.onStatusChanged(account)
            } else {
                if (account.status == Account.State.NO_INTERNET) {
                    account.status = Account.State.OFFLINE
                    statusListener?.onStatusChanged(account)
                }
                if (account.status == Account.State.ONLINE) {
                    synchronized(mLowPingTimeoutMode) {
                        val lastReceived = account.xmppConnection.lastPacketReceived
                        val lastSent = account.xmppConnection.lastPingSent
                        val pingInterval =
                            (if (isUiAction) Config.PING_MIN_INTERVAL * 1000 else Config.PING_MAX_INTERVAL * 1000).toLong()
                        val msToNextPing = Math.max(
                            lastReceived,
                            lastSent
                        ) + pingInterval - SystemClock.elapsedRealtime()
                        val pingTimeout =
                            if (mLowPingTimeoutMode.contains(account.jid.asBareJid())) Config.LOW_PING_TIMEOUT * 1000 else Config.PING_TIMEOUT * 1000
                        val pingTimeoutIn = lastSent + pingTimeout - SystemClock.elapsedRealtime()
                        if (lastSent > lastReceived) {
                            if (pingTimeoutIn < 0) {
                                Log.d(
                                    Config.LOGTAG,
                                    account.jid.asBareJid().toString() + ": ping timeout"
                                )
                                this.reconnectAccount(account, true, interactive)
                            } else {
                                val secs = (pingTimeoutIn / 1000).toInt()
                                this.scheduleWakeUpCall(secs, account.uuid.hashCode())
                            }
                        } else {
                            pingCandidates.add(account)
                            if (isAccountPushed) {
                                pingNow = true
                                if (mLowPingTimeoutMode.add(account.jid.asBareJid())) {
                                    Log.d(
                                        Config.LOGTAG,
                                        account.jid.asBareJid().toString() + ": entering low ping timeout mode"
                                    )
                                }
                            } else if (msToNextPing <= 0) {
                                pingNow = true
                            } else {
                                this.scheduleWakeUpCall(
                                    (msToNextPing / 1000).toInt(),
                                    account.uuid.hashCode()
                                )
                                if (mLowPingTimeoutMode.remove(account.jid.asBareJid())) {
                                    Log.d(
                                        Config.LOGTAG,
                                        account.jid.asBareJid().toString() + ": leaving low ping timeout mode"
                                    )
                                }
                            }
                        }
                    }
                } else if (account.status == Account.State.OFFLINE) {
                    reconnectAccount(account, true, interactive)
                } else if (account.status == Account.State.CONNECTING) {
                    val secondsSinceLastConnect =
                        (SystemClock.elapsedRealtime() - account.xmppConnection.lastConnect) / 1000
                    val secondsSinceLastDisco =
                        (SystemClock.elapsedRealtime() - account.xmppConnection.lastDiscoStarted) / 1000
                    val discoTimeout = Config.CONNECT_DISCO_TIMEOUT - secondsSinceLastDisco
                    val timeout = Config.CONNECT_TIMEOUT - secondsSinceLastConnect
                    if (timeout < 0) {
                        Log.d(
                            Config.LOGTAG,
                            account.jid.toString() + ": time out during connect reconnecting (secondsSinceLast=" + secondsSinceLastConnect + ")"
                        )
                        account.xmppConnection.resetAttemptCount(false)
                        reconnectAccount(account, true, interactive)
                    } else if (discoTimeout < 0) {
                        account.xmppConnection.sendDiscoTimeout()
                        scheduleWakeUpCall(
                            Math.min(timeout, discoTimeout).toInt(),
                            account.uuid.hashCode()
                        )
                    } else {
                        scheduleWakeUpCall(
                            Math.min(timeout, discoTimeout).toInt(),
                            account.uuid.hashCode()
                        )
                    }
                } else {
                    if (account.xmppConnection.timeToNextAttempt <= 0) {
                        reconnectAccount(account, true, interactive)
                    }
                }
            }
        }
        return pingNow
    }

    fun discoverChannels(query: String?, onChannelSearchResultsFound: OnChannelSearchResultsFound) {
        Timber.d("discover channels. query=" + query!!)
        if (query == null || query.trim { it <= ' ' }.isEmpty()) {
            discoverChannelsInternal(onChannelSearchResultsFound)
        } else {
            discoverChannelsInternal(query, onChannelSearchResultsFound)
        }
    }

    fun discoverChannelsInternal(listener: OnChannelSearchResultsFound) {
        val call = muclumbusService!!.getRooms(1)
        try {
            call.enqueue(object : Callback<MuclumbusService.Rooms> {
                override fun onResponse(
                    call: Call<MuclumbusService.Rooms>,
                    response: Response<MuclumbusService.Rooms>
                ) {
                    val body = response.body() ?: return
                    listener.onChannelSearchResultsFound(body.items)
                }

                override fun onFailure(call: Call<MuclumbusService.Rooms>, throwable: Throwable) {

                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    fun discoverChannelsInternal(query: String, listener: OnChannelSearchResultsFound) {
        val searchResultCall = muclumbusService!!.search(MuclumbusService.SearchRequest(query))

        searchResultCall.enqueue(object : Callback<MuclumbusService.SearchResult> {
            override fun onResponse(
                call: Call<MuclumbusService.SearchResult>,
                response: Response<MuclumbusService.SearchResult>
            ) {
                println(response.message())
                val body = response.body() ?: return
                listener.onChannelSearchResultsFound(body.result.items)
            }

            override fun onFailure(
                call: Call<MuclumbusService.SearchResult>,
                throwable: Throwable
            ) {
                throwable.printStackTrace()
            }
        })
    }

    fun directReply(conversation: Conversation, body: String, dismissAfterReply: Boolean) {
        val message = Message(conversation, body, conversation.nextEncryption)
        message.markUnread()
        if (message.encryption == Message.ENCRYPTION_PGP) {
            pgpEngine!!.encrypt(message, object : UiCallback<Message> {
                override fun success(message: Message) {
                    if (dismissAfterReply) {
                        markRead(message.conversation as Conversation, true)
                    } else {
                        notificationService.pushFromDirectReply(message)
                    }
                }

                override fun error(errorCode: Int, `object`: Message) {

                }

                override fun userInputRequried(pi: PendingIntent, `object`: Message) {

                }
            })
        } else {
            sendMessage(message)
            if (dismissAfterReply) {
                markRead(conversation, true)
            } else {
                notificationService.pushFromDirectReply(message)
            }
        }
    }

    fun dndOnSilentMode(): Boolean {
        return getBooleanPreference(SettingsActivity.DND_ON_SILENT_MODE, R.bool.dnd_on_silent_mode)
    }

    fun manuallyChangePresence(): Boolean {
        return getBooleanPreference(
            SettingsActivity.MANUALLY_CHANGE_PRESENCE,
            R.bool.manually_change_presence
        )
    }

    fun treatVibrateAsSilent(): Boolean {
        return getBooleanPreference(
            SettingsActivity.TREAT_VIBRATE_AS_SILENT,
            R.bool.treat_vibrate_as_silent
        )
    }

    fun awayWhenScreenOff(): Boolean {
        return getBooleanPreference(
            SettingsActivity.AWAY_WHEN_SCREEN_IS_OFF,
            R.bool.away_when_screen_off
        )
    }

    fun resetAllAttemptCounts(reallyAll: Boolean, retryImmediately: Boolean) {
        Timber.d("resetting all attempt counts")
        for (account in accounts!!) {
            if (account.hasErrorStatus() || reallyAll) {
                val connection = account.xmppConnection
                connection?.resetAttemptCount(retryImmediately)
            }
            if (account.setShowErrorNotification(true)) {
                mDatabaseWriterExecutor.execute { databaseBackend.updateAccount(account) }
            }
        }
        notificationService.updateErrorNotification()
    }

    fun dismissErrorNotifications() {
        for (account in this.accounts!!) {
            if (account.hasErrorStatus()) {
                Log.d(
                    Config.LOGTAG,
                    account.jid.asBareJid().toString() + ": dismissing error notification"
                )
                if (account.setShowErrorNotification(false)) {
                    mDatabaseWriterExecutor.execute { databaseBackend.updateAccount(account) }
                }
            }
        }
    }

    fun expireOldMessages() {
        expireOldMessages(false)
    }

    fun expireOldMessages(resetHasMessagesLeftOnServer: Boolean) {
        mLastExpiryRun.set(SystemClock.elapsedRealtime())
        mDatabaseWriterExecutor.execute {
            val timestamp = automaticMessageDeletionDate
            if (timestamp > 0) {
                databaseBackend.expireOldMessages(timestamp)
                synchronized(this@XmppConnectionService.conversations) {
                    for (conversation in this@XmppConnectionService.conversations) {
                        conversation.expireOldMessages(timestamp)
                        if (resetHasMessagesLeftOnServer) {
                            conversation.messagesLoaded.set(true)
                            conversation.setHasMessagesLeftOnServer(true)
                        }
                    }
                }
                updateConversationUi()
            }
        }
    }

    fun hasInternetConnection(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            val activeNetwork = cm?.activeNetworkInfo
            return activeNetwork != null && (activeNetwork.isConnected || activeNetwork.type == ConnectivityManager.TYPE_ETHERNET)
        } catch (e: RuntimeException) {
            Timber.d("unable to check for internet connection", e)
            return true //if internet connection can not be checked it is probably best to just try
        }

    }

    @SuppressLint("TrulyRandom")
    override fun onCreate() {
        if (Compatibility.runsTwentySix()) {
            notificationService.initializeChannels()
        }
        mForceDuringOnCreate.set(Compatibility.runsAndTargetsTwentySix(this))
        toggleForegroundService()
        this.destroyed = false
        OmemoSetting.load(this)
        ExceptionHelper.init(applicationContext)
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (throwable: Throwable) {
            Log.e(Config.LOGTAG, "unable to initialize security provider", throwable)
        }

        Resolver.init(this)
        this.rng = SecureRandom()
        updateMemorizingTrustmanager()
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        this.bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
        if (mLastActivity == 0L) {
            mLastActivity =
                preferences.getLong(SETTING_LAST_ACTIVITY_TS, System.currentTimeMillis())
        }

        Timber.d("initializing database...")
        this.databaseBackend = DatabaseBackend.getInstance(applicationContext)
        Timber.d("restoring accounts...")
        this.accounts = databaseBackend.accounts
        val editor = preferences.edit()
        if (this.accounts!!.size == 0 && Arrays.asList(
                "Sony",
                "Sony Ericsson"
            ).contains(Build.MANUFACTURER)
        ) {
            editor.putBoolean(SettingsActivity.KEEP_FOREGROUND_SERVICE, true)
            Log.d(
                Config.LOGTAG,
                Build.MANUFACTURER + " is on blacklist. enabling foreground service"
            )
        }
        val hasEnabledAccounts = hasEnabledAccounts()
        editor.putBoolean(EventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts).apply()
        editor.apply()
        toggleSetProfilePictureActivity(hasEnabledAccounts)

        restoreFromDatabase()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startContactObserver()
        }
        if (Compatibility.hasStoragePermission(this)) {
            Timber.d("starting file observer")
            mFileAddingExecutor.execute { this.fileObserver.startWatching() }
            mFileAddingExecutor.execute { this.checkForDeletedFiles() }
        }
        if (Config.supportOpenPgp()) {
            this.pgpServiceConnection = OpenPgpServiceConnection(
                this,
                "org.sufficientlysecure.keychain",
                object : OpenPgpServiceConnection.OnBound {
                    override fun onBound(service: IOpenPgpService2) {
                        for (account in accounts!!) {
                            val pgp = account.pgpDecryptionService
                            pgp?.continueDecryption(true)
                        }
                    }

                    override fun onError(e: Exception) {}
                })
            this.pgpServiceConnection!!.bindToService()
        }

        this.pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        this.wakeLock = pm!!.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Conversations:Service")

        toggleForegroundService()
        updateUnreadCountBadge()
        toggleScreenEventReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scheduleNextIdlePing()
            val intentFilter = IntentFilter()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
            intentFilter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            registerReceiver(this.mInternalEventReceiver, intentFilter)
        }
        mForceDuringOnCreate.set(false)
        toggleForegroundService()

        val retrofit = Retrofit.Builder()
            .baseUrl(Config.CHANNEL_DISCOVERY)
            .addConverterFactory(GsonConverterFactory.create())
            .callbackExecutor(Executors.newSingleThreadExecutor())
            .build()
        muclumbusService = retrofit.create(MuclumbusService::class.java)
    }

    fun checkForDeletedFiles() {
        if (destroyed) {
            Log.d(
                Config.LOGTAG,
                "Do not check for deleted files because service has been destroyed"
            )
            return
        }
        val start = SystemClock.elapsedRealtime()
        val relativeFilePaths = databaseBackend.filePathInfo
        val changed = ArrayList<DatabaseBackend.FilePathInfo>()
        for (filePath in relativeFilePaths) {
            if (destroyed) {
                Log.d(
                    Config.LOGTAG,
                    "Stop checking for deleted files because service has been destroyed"
                )
                return
            }
            val file = fileBackend.getFileForPath(filePath.path)
            if (filePath.setDeleted(!file.exists())) {
                changed.add(filePath)
            }
        }
        val duration = SystemClock.elapsedRealtime() - start
        Log.d(
            Config.LOGTAG,
            "found " + changed.size + " changed files on start up. total=" + relativeFilePaths.size + ". (" + duration + "ms)"
        )
        if (changed.size > 0) {
            databaseBackend.markFilesAsChanged(changed)
            markChangedFiles(changed)
        }
    }

    fun startContactObserver() {
        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    if (restoredFromDatabaseLatch.count == 0L) {
                        loadPhoneContacts()
                    }
                }
            })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            Timber.d("clear cache due to low memory")
            bitmapCache!!.evictAll()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(this.mInternalEventReceiver)
        } catch (e: IllegalArgumentException) {
            //ignored
        }

        destroyed = false
        fileObserver.stopWatching()
        super.onDestroy()
    }

    fun restartFileObserver() {
        Timber.d("restarting file observer")
        mFileAddingExecutor.execute { this.fileObserver.restartWatching() }
        mFileAddingExecutor.execute { this.checkForDeletedFiles() }
    }

    fun toggleScreenEventReceiver() {
        if (awayWhenScreenOff() && !manuallyChangePresence()) {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            registerReceiver(this.mInternalScreenEventReceiver, filter)
        } else {
            try {
                unregisterReceiver(this.mInternalScreenEventReceiver)
            } catch (e: IllegalArgumentException) {
                //ignored
            }

        }
    }

    fun toggleForegroundService() {
        toggleForegroundService(false)
    }

    fun toggleForegroundService(force: Boolean) {
        val status: Boolean
        if (force || mForceDuringOnCreate.get() || mForceForegroundService.get() || Compatibility.keepForegroundService(
                this
            ) && hasEnabledAccounts()
        ) {
            val notification = this.notificationService.createForegroundNotification()
            startForeground(NotificationService.FOREGROUND_NOTIFICATION_ID, notification)
            if (!mForceForegroundService.get()) {
                notificationService.notify(
                    NotificationService.FOREGROUND_NOTIFICATION_ID,
                    notification
                )
            }
            status = true
        } else {
            stopForeground(true)
            status = false
        }
        if (!mForceForegroundService.get()) {
            notificationService.dismissForcedForegroundNotification() //if the channel was changed the previous call might fail
        }
        Timber.d("ForegroundService: " + if (status) "on" else "off")
    }

    fun foregroundNotificationNeedsUpdatingWhenErrorStateChanges(): Boolean {
        return !mForceForegroundService.get() && Compatibility.keepForegroundService(this) && hasEnabledAccounts()
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)
        if (Compatibility.keepForegroundService(this) && hasEnabledAccounts() || mForceForegroundService.get()) {
            Timber.d("ignoring onTaskRemoved because foreground service is activated")
        } else {
            this.logoutAndSave(false)
        }
    }

    fun logoutAndSave(stop: Boolean) {
        var activeAccounts = 0
        for (account in accounts!!) {
            if (account.status != Account.State.DISABLED) {
                databaseBackend.writeRoster(account.roster)
                activeAccounts++
            }
            if (account.xmppConnection != null) {
                Thread { disconnect(account, false) }.start()
            }
        }
        if (stop || activeAccounts == 0) {
            Timber.d("good bye")
            stopSelf()
        }
    }

    fun schedulePostConnectivityChange() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager ?: return
        val triggerAtMillis =
            SystemClock.elapsedRealtime() + Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL * 1000
        val intent = Intent(this, EventReceiver::class.java)
        intent.action = ACTION_POST_CONNECTIVITY_CHANGE
        try {
            val pendingIntent = PendingIntent.getBroadcast(this, 1, intent, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: RuntimeException) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for post connectivity change", e)
        }

    }

    fun scheduleWakeUpCall(seconds: Int, requestCode: Int) {
        val timeToWake =
            SystemClock.elapsedRealtime() + (if (seconds < 0) 1 else seconds + 1) * 1000
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager ?: return
        val intent = Intent(this, EventReceiver::class.java)
        intent.action = "ping"
        try {
            val pendingIntent = PendingIntent.getBroadcast(this, requestCode, intent, 0)
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent)
        } catch (e: RuntimeException) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for ping", e)
        }

    }

    @TargetApi(Build.VERSION_CODES.M)
    fun scheduleNextIdlePing() {
        val timeToWake = SystemClock.elapsedRealtime() + Config.IDLE_PING_INTERVAL * 1000
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager ?: return
        val intent = Intent(this, EventReceiver::class.java)
        intent.action = ACTION_IDLE_PING
        try {
            val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                timeToWake,
                pendingIntent
            )
        } catch (e: RuntimeException) {
            Timber.d("unable to schedule alarm for idle ping", e)
        }

    }

    fun createConnection(account: Account): XmppConnection {
        val connection = XmppConnection(account, this)
        connection.setOnMessagePacketReceivedListener(this.mMessageParser)
        connection.setOnStatusChangedListener(this.statusListener)
        connection.setOnPresencePacketReceivedListener(this.mPresenceParser)
        connection.setOnUnregisteredIqPacketReceivedListener(this.iqParser)
        connection.setOnJinglePacketReceivedListener(this.jingleListener)
        connection.setOnBindListener(this.mOnBindListener)
        connection.setOnMessageAcknowledgeListener(this.mOnMessageAcknowledgedListener)
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.messageArchiveService)
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.avatarService)
        val axolotlService = account.axolotlService
        if (axolotlService != null) {
            connection.addOnAdvancedStreamFeaturesAvailableListener(axolotlService)
        }
        return connection
    }

    fun sendChatState(conversation: Conversation) {
        if (sendChatStates()) {
            val packet = messageGenerator.generateChatState(conversation)
            sendMessagePacket(conversation.account, packet)
        }
    }

    fun sendFileMessage(message: Message, delay: Boolean) {
        Timber.d("send file message")
        val account = message.conversation.account
        if (account.httpUploadAvailable(
                fileBackend.getFile(
                    message,
                    false
                ).size
            ) || message.conversation.mode == Conversation.MODE_MULTI
        ) {
            httpConnectionManager.createNewUploadConnection(message, delay)
        } else {
            jingleConnectionManager.createNewConnection(message)
        }
    }

    fun sendMessage(message: Message) {
        sendMessage(message, false, false)
    }

    fun sendMessage(message: Message, resend: Boolean, delay: Boolean) {
        val account = message.conversation.account
        if (account.setShowErrorNotification(true)) {
            databaseBackend.updateAccount(account)
            notificationService.updateErrorNotification()
        }
        val conversation = message.conversation as Conversation
        account.deactivateGracePeriod()


        if (QuickConversationsService.isQuicksy() && conversation.mode == Conversation.MODE_SINGLE) {
            val contact = conversation.contact
            if (!contact.showInRoster() && contact.getOption(Contact.Options.SYNCED_VIA_OTHER)) {
                Log.d(
                    Config.LOGTAG,
                    account.jid.asBareJid().toString() + ": adding " + contact.jid + " on sending message"
                )
                createContact(contact, true)
            }
        }

        var packet: MessagePacket? = null
        val addToConversation =
            (conversation.mode != Conversation.MODE_MULTI || !Patches.BAD_MUC_REFLECTION.contains(
                account.serverIdentity
            )) && !message.edited()
        var saveInDb = addToConversation
        message.status = Message.STATUS_WAITING

        if (message.encryption != Message.ENCRYPTION_NONE && conversation.mode == Conversation.MODE_MULTI && conversation.isPrivateAndNonAnonymous) {
            if (conversation.setAttribute(
                    Conversation.ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS,
                    true
                )
            ) {
                databaseBackend.updateConversation(conversation)
            }
        }

        if (account.isOnlineAndConnected) {
            when (message.encryption) {
                Message.ENCRYPTION_NONE -> if (message.needsUploading()) {
                    if (account.httpUploadAvailable(fileBackend.getFile(message, false).size)
                        || conversation.mode == Conversation.MODE_MULTI
                        || message.fixCounterpart()
                    ) {
                        this.sendFileMessage(message, delay)
                    }
                } else {
                    packet = messageGenerator.generateChat(message)
                }
                Message.ENCRYPTION_PGP, Message.ENCRYPTION_DECRYPTED -> if (message.needsUploading()) {
                    if (account.httpUploadAvailable(fileBackend.getFile(message, false).size)
                        || conversation.mode == Conversation.MODE_MULTI
                        || message.fixCounterpart()
                    ) {
                        this.sendFileMessage(message, delay)
                    }
                } else {
                    packet = messageGenerator.generatePgpChat(message)
                }
                Message.ENCRYPTION_AXOLOTL -> {
                    message.fingerprint = account.axolotlService.ownFingerprint
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(fileBackend.getFile(message, false).size)
                            || conversation.mode == Conversation.MODE_MULTI
                            || message.fixCounterpart()
                        ) {
                            this.sendFileMessage(message, delay)
                        }
                    } else {
                        val axolotlMessage =
                            account.axolotlService.fetchAxolotlMessageFromCache(message)
                        if (axolotlMessage == null) {
                            account.axolotlService.preparePayloadMessage(message, delay)
                        } else {
                            packet = messageGenerator.generateAxolotlChat(message, axolotlMessage)
                        }
                    }
                }
            }
            if (packet != null) {
                if (account.xmppConnection.features.sm() || conversation.mode == Conversation.MODE_MULTI && message.counterpart.isBareJid) {
                    message.status = Message.STATUS_UNSEND
                } else {
                    message.status = Message.STATUS_SEND
                }
            }
        } else {
            when (message.encryption) {
                Message.ENCRYPTION_DECRYPTED -> if (!message.needsUploading()) {
                    val pgpBody = message.encryptedBody
                    val decryptedBody = message.body
                    message.body = pgpBody //TODO might throw NPE
                    message.encryption = Message.ENCRYPTION_PGP
                    if (message.edited()) {
                        message.body = decryptedBody
                        message.encryption = Message.ENCRYPTION_DECRYPTED
                        if (!databaseBackend.updateMessage(message, message.editedId)) {
                            Log.e(Config.LOGTAG, "error updated message in DB after edit")
                        }
                        updateConversationUi()
                        return
                    } else {
                        databaseBackend.createMessage(message)
                        saveInDb = false
                        message.body = decryptedBody
                        message.encryption = Message.ENCRYPTION_DECRYPTED
                    }
                }
                Message.ENCRYPTION_AXOLOTL -> message.fingerprint =
                    account.axolotlService.ownFingerprint
            }
        }


        val mucMessage =
            conversation.mode == Conversation.MODE_MULTI && message.type != Message.TYPE_PRIVATE
        if (mucMessage) {
            message.counterpart = conversation.mucOptions.self.fullJid
        }

        if (resend) {
            if (packet != null && addToConversation) {
                if (account.xmppConnection.features.sm() || mucMessage) {
                    markMessage(message, Message.STATUS_UNSEND)
                } else {
                    markMessage(message, Message.STATUS_SEND)
                }
            }
        } else {
            if (addToConversation) {
                conversation.add(message)
            }
            if (saveInDb) {
                databaseBackend.createMessage(message)
            } else if (message.edited()) {
                if (!databaseBackend.updateMessage(message, message.editedId)) {
                    Log.e(Config.LOGTAG, "error updated message in DB after edit")
                }
            }
            updateConversationUi()
        }
        if (packet != null) {
            if (delay) {
                messageGenerator.addDelay(packet, message.timeSent)
            }
            if (conversation.setOutgoingChatState(Config.DEFAULT_CHATSTATE)) {
                if (this.sendChatStates()) {
                    packet.addChild(ChatState.toElement(conversation.outgoingChatState))
                }
            }
            sendMessagePacket(account, packet)
        }
    }

    fun sendUnsentMessages(conversation: Conversation) {
        conversation.findWaitingMessages { message -> resendMessage(message, true) }
    }

    fun resendMessage(message: Message, delay: Boolean) {
        sendMessage(message, true, delay)
    }

    fun fetchRosterFromServer(account: Account) {
        val iqPacket = IqPacket(IqPacket.TYPE.GET)
        if ("" != account.rosterVersion) {
            Log.d(
                Config.LOGTAG, account.jid.asBareJid().toString()
                        + ": fetching roster version " + account.rosterVersion
            )
        } else {
            Timber.d(account.jid.asBareJid().toString() + ": fetching roster")
        }
        iqPacket.query(Namespace.ROSTER).setAttribute("ver", account.rosterVersion)
        sendIqPacket(account, iqPacket, iqParser)
    }

    fun fetchBookmarks(account: Account) {
        val iqPacket = IqPacket(IqPacket.TYPE.GET)
        val query = iqPacket.query("jabber:iq:private")
        query.addChild("storage", Namespace.BOOKMARKS)
        val callback = OnIqPacketReceived { a, response ->
            if (response.getType() == IqPacket.TYPE.RESULT) {
                val query1 = response.query()
                val storage = query1.findChild("storage", "storage:bookmarks")
                processBookmarks(a, storage, false)
            } else {
                Timber.d(a.getJid().asBareJid().toString() + ": could not fetch bookmarks")
            }
        }
        sendIqPacket(account, iqPacket, callback)
    }

    fun processBookmarks(account: Account, storage: Element?, pep: Boolean) {
        val previousBookmarks = account.bookmarkedJids
        val bookmarks = HashMap<Jid, Bookmark>()
        val synchronizeWithBookmarks = synchronizeWithBookmarks()
        if (storage != null) {
            for (item in storage.children) {
                if (item.name == "conference") {
                    val bookmark = Bookmark.parse(item, account)
                    val old = bookmarks.put(bookmark.jid, bookmark)
                    if (old != null && old.bookmarkName != null && bookmark.bookmarkName == null) {
                        bookmark.bookmarkName = old.bookmarkName
                    }
                    if (bookmark.jid == null) {
                        continue
                    }
                    previousBookmarks.remove(bookmark.jid.asBareJid())
                    var conversation = find(bookmark)
                    if (conversation != null) {
                        if (conversation.mode != Conversation.MODE_MULTI) {
                            continue
                        }
                        bookmark.conversation = conversation
                        if (pep && synchronizeWithBookmarks && !bookmark.autojoin()) {
                            Log.d(
                                Config.LOGTAG,
                                account.jid.asBareJid().toString() + ": archiving conference (" + conversation.jid + ") after receiving pep"
                            )
                            archiveConversation(conversation, false)
                        }
                    } else if (synchronizeWithBookmarks && bookmark.autojoin()) {
                        conversation =
                            findOrCreateConversation(account, bookmark.fullJid, true, true, false)
                        bookmark.conversation = conversation
                    }
                }
            }
            if (pep && synchronizeWithBookmarks) {
                Log.d(
                    Config.LOGTAG,
                    account.jid.asBareJid().toString() + ": " + previousBookmarks.size + " bookmarks have been removed"
                )
                for (jid in previousBookmarks) {
                    val conversation = find(account, jid)
                    if (conversation != null && conversation.mucOptions.error == MucOptions.Error.DESTROYED) {
                        Log.d(
                            Config.LOGTAG,
                            account.jid.asBareJid().toString() + ": archiving destroyed conference (" + conversation.jid + ") after receiving pep"
                        )
                        archiveConversation(conversation, false)
                    }
                }
            }
        }
        account.setBookmarks(CopyOnWriteArrayList(bookmarks.values))
    }

    fun pushBookmarks(account: Account) {
        if (account.xmppConnection.features.bookmarksConversion()) {
            pushBookmarksPep(account)
        } else {
            pushBookmarksPrivateXml(account)
        }
    }

    fun pushBookmarksPrivateXml(account: Account) {
        Log.d(
            Config.LOGTAG,
            account.jid.asBareJid().toString() + ": pushing bookmarks via xml"
        )
        val iqPacket = IqPacket(IqPacket.TYPE.SET)
        val query = iqPacket.query("jabber:iq:private")
        val storage = query.addChild("storage", "storage:bookmarks")
        for (bookmark in account.bookmarks) {
            storage.addChild(bookmark)
        }
        sendIqPacket(account, iqPacket, mDefaultIqHandler)
    }

    fun pushBookmarksPep(account: Account) {
        Timber.d(account.jid.asBareJid().toString() + ": pushing bookmarks via pep")
        val storage = Element("storage", "storage:bookmarks")
        for (bookmark in account.bookmarks) {
            storage.addChild(bookmark)
        }
        pushNodeAndEnforcePublishOptions(
            account,
            Namespace.BOOKMARKS,
            storage,
            PublishOptions.persistentWhitelistAccess()
        )

    }

    fun pushNodeAndEnforcePublishOptions(
        account: Account,
        node: String,
        element: Element,
        options: Bundle,
        retry: Boolean = true
    ) {
        val packet = iqGenerator.publishElement(node, element, options)
        sendIqPacket(account, packet, OnIqPacketReceived { a, response ->
            if (response.getType() == IqPacket.TYPE.RESULT) {
                return@OnIqPacketReceived
            }
            if (retry && PublishOptions.preconditionNotMet(response)) {
                pushNodeConfiguration(account, node, options, object : OnConfigurationPushed {
                    override fun onPushSucceeded() {
                        pushNodeAndEnforcePublishOptions(account, node, element, options, false)
                    }

                    override fun onPushFailed() {
                        Log.d(
                            Config.LOGTAG,
                            account.jid.asBareJid().toString() + ": unable to push node configuration (" + node + ")"
                        )
                    }
                })
            } else {
                Log.d(
                    Config.LOGTAG,
                    account.jid.asBareJid().toString() + ": error publishing bookmarks (retry=" + java.lang.Boolean.toString(
                        retry
                    ) + ") " + response
                )
            }
        })
    }

    fun restoreFromDatabase() {
        synchronized(this.conversations) {
            val accountLookupTable = Hashtable<String, Account>()
            for (account in this.accounts!!) {
                accountLookupTable[account.uuid] = account
            }
            Timber.d("restoring conversations...")
            val startTimeConversationsRestore = SystemClock.elapsedRealtime()
            this.conversations.addAll(databaseBackend.getConversations(Conversation.STATUS_AVAILABLE))
            val iterator = conversations.listIterator()
            while (iterator.hasNext()) {
                val conversation = iterator.next()
                val account = accountLookupTable[conversation.accountUuid]
                if (account != null) {
                    conversation.account = account
                } else {
                    Log.e(Config.LOGTAG, "unable to restore Conversations with " + conversation.jid)
                    iterator.remove()
                }
            }
            val diffConversationsRestore =
                SystemClock.elapsedRealtime() - startTimeConversationsRestore
            Log.d(
                Config.LOGTAG,
                "finished restoring conversations in " + diffConversationsRestore + "ms"
            )
            val runnable = {
                val deletionDate = automaticMessageDeletionDate
                mLastExpiryRun.set(SystemClock.elapsedRealtime())
                if (deletionDate > 0) {
                    Log.d(
                        Config.LOGTAG,
                        "deleting messages that are older than " + AbstractGenerator.getTimestamp(
                            deletionDate
                        )
                    )
                    databaseBackend.expireOldMessages(deletionDate)
                }
                Timber.d("restoring roster...")
                for (account in accounts!!) {
                    databaseBackend.readRoster(account.roster)
                    account.initAccountServices(this@XmppConnectionService) //roster needs to be loaded at this stage
                }
                bitmapCache!!.evictAll()
                loadPhoneContacts()
                Timber.d("restoring messages...")
                val startMessageRestore = SystemClock.elapsedRealtime()
                val quickLoad = QuickLoader.get(this.conversations)
                if (quickLoad != null) {
                    restoreMessages(quickLoad)
                    updateConversationUi()
                    val diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore
                    Log.d(
                        Config.LOGTAG,
                        "quickly restored " + quickLoad.name + " after " + diffMessageRestore + "ms"
                    )
                }
                for (conversation in this.conversations) {
                    if (quickLoad !== conversation) {
                        restoreMessages(conversation)
                    }
                }
                notificationService.finishBacklog(false)
                restoredFromDatabaseLatch.countDown()
                val diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore
                Timber.d("finished restoring messages in " + diffMessageRestore + "ms")
                updateConversationUi()
            }
            mDatabaseReaderExecutor.execute(runnable) //will contain one write command (expiry) but that's fine
        }
    }

    fun restoreMessages(conversation: Conversation) {
        conversation.addAll(0, databaseBackend.getMessages(conversation, Config.PAGE_SIZE))
        conversation.findUnsentTextMessages { message ->
            markMessage(
                message,
                Message.STATUS_WAITING
            )
        }
        conversation.findUnreadMessages { message -> notificationService.pushFromBacklog(message) }
    }

    fun loadPhoneContacts() {
        mContactMergerExecutor.execute {
            val contacts = JabberIdContact.load(this)
            Timber.d("start merging phone contacts with roster")
            for (account in accounts!!) {
                val withSystemAccounts =
                    account.roster.getWithSystemAccounts(JabberIdContact::class.java)
                for (jidContact in contacts.values) {
                    val contact = account.roster.getContact(jidContact.jid)
                    val needsCacheClean = contact.setPhoneContact(jidContact)
                    if (needsCacheClean) {
                        avatarService.clear(contact)
                    }
                    withSystemAccounts.remove(contact)
                }
                for (contact in withSystemAccounts) {
                    val needsCacheClean = contact.unsetPhoneContact(JabberIdContact::class.java)
                    if (needsCacheClean) {
                        avatarService.clear(contact)
                    }
                }
            }
            Timber.d("finished merging phone contacts")
            shortcutService.refresh(mInitialAddressbookSyncCompleted.compareAndSet(false, true))
            updateRosterUi()
            quickConversationsService.considerSync()
        }
    }


    fun syncRoster(account: Account) {
        mRosterSyncTaskManager.execute(account) { databaseBackend.writeRoster(account.roster) }
    }

    fun getConversations(): List<Conversation> {
        return this.conversations
    }

    fun markFileDeleted(path: String) {
        val file = File(path)
        val isInternalFile = fileBackend.isInternalFile(file)
        val uuids = databaseBackend.markFileAsDeleted(file, isInternalFile)
        Log.d(
            Config.LOGTAG,
            "deleted file " + path + " internal=" + isInternalFile + ", database hits=" + uuids.size
        )
        markUuidsAsDeletedFiles(uuids)
    }

    fun markUuidsAsDeletedFiles(uuids: List<String>) {
        var deleted = false
        for (conversation in getConversations()) {
            deleted = deleted or conversation.markAsDeleted(uuids)
        }
        if (deleted) {
            updateConversationUi()
        }
    }

    fun markChangedFiles(infos: List<DatabaseBackend.FilePathInfo>) {
        var changed = false
        for (conversation in getConversations()) {
            changed = changed or conversation.markAsChanged(infos)
        }
        if (changed) {
            updateConversationUi()
        }
    }

    @JvmOverloads
    fun populateWithOrderedConversations(
        list: MutableList<Conversation>,
        includeNoFileUpload: Boolean = true,
        sort: Boolean = true
    ) {
        val orderedUuids: MutableList<String>?
        if (sort) {
            orderedUuids = null
        } else {
            orderedUuids = ArrayList()
            for (conversation in list) {
                orderedUuids.add(conversation.uuid)
            }
        }
        list.clear()
        if (includeNoFileUpload) {
            list.addAll(getConversations())
        } else {
            for (conversation in getConversations()) {
                if (conversation.mode == Conversation.MODE_SINGLE || conversation.account.httpUploadAvailable() && conversation.mucOptions.participating()) {
                    list.add(conversation)
                }
            }
        }
        try {
            if (orderedUuids != null) {
                sort(list) { a, b ->
                    val indexA = orderedUuids.indexOf(a.uuid)
                    val indexB = orderedUuids.indexOf(b.uuid)
                    if (indexA == -1 || indexB == -1 || indexA == indexB)
                        a.compareTo(b)
                    else
                        indexA - indexB
                }
            } else {
                sort(list)
            }
        } catch (e: IllegalArgumentException) {
            //ignore
        }

    }

    fun loadMoreMessages(
        conversation: Conversation,
        timestamp: Long,
        callback: OnMoreMessagesLoaded
    ) {
        if (this@XmppConnectionService.messageArchiveService.queryInProgress(
                conversation,
                callback
            )
        ) {
            return
        } else if (timestamp == 0L) {
            return
        }
        Log.d(
            Config.LOGTAG,
            "load more messages for " + conversation.name + " prior to " + MessageGenerator.getTimestamp(
                timestamp
            )
        )
        val runnable = {
            val account = conversation.account
            val messages = databaseBackend.getMessages(conversation, 50, timestamp)
            if (messages.size > 0) {
                conversation.addAll(0, messages)
                callback.onMoreMessagesLoaded(messages.size, conversation)
            } else if (conversation.hasMessagesLeftOnServer()
                && account.isOnlineAndConnected
                && conversation.lastClearHistory.timestamp == 0L
            ) {
                val mamAvailable: Boolean
                if (conversation.mode == Conversation.MODE_SINGLE) {
                    mamAvailable =
                        account.xmppConnection.features.mam() && !conversation.contact.isBlocked
                } else {
                    mamAvailable = conversation.mucOptions.mamSupport()
                }
                if (mamAvailable) {
                    val query =
                        messageArchiveService.query(conversation, MamReference(0), timestamp, false)
                    if (query != null) {
                        query.setCallback(callback)
                        callback.informUser(R.string.fetching_history_from_server)
                    } else {
                        callback.informUser(R.string.not_fetching_history_retention_period)
                    }

                }
            }
        }
        mDatabaseReaderExecutor.execute(runnable)
    }


    /**
     * This will find all conferences with the contact as member and also the conference that is the contact (that 'fake' contact is used to store the avatar)
     */
    fun findAllConferencesWith(contact: Contact): List<Conversation> {
        val results = ArrayList<Conversation>()
        for (c in conversations) {
            if (c.mode == Conversation.MODE_MULTI && (c.jid.asBareJid() == contact.jid.asBareJid() || c.mucOptions.isContactInRoom(
                    contact
                ))
            ) {
                results.add(c)
            }
        }
        return results
    }

    fun find(haystack: Iterable<Conversation>, contact: Contact): Conversation? {
        for (conversation in haystack) {
            if (conversation.contact === contact) {
                return conversation
            }
        }
        return null
    }

    fun find(haystack: Iterable<Conversation>, account: Account?, jid: Jid?): Conversation? {
        if (jid == null) {
            return null
        }
        for (conversation in haystack) {
            if ((account == null || conversation.account === account) && conversation.jid.asBareJid() == jid.asBareJid()) {
                return conversation
            }
        }
        return null
    }

    fun isConversationsListEmpty(ignore: Conversation?): Boolean {
        synchronized(this.conversations) {
            val size = this.conversations.size
            return size == 0 || size == 1 && this.conversations[0] === ignore
        }
    }

    fun isConversationStillOpen(conversation: Conversation): Boolean {
        synchronized(this.conversations) {
            for (current in this.conversations) {
                if (current === conversation) {
                    return true
                }
            }
        }
        return false
    }

    fun findOrCreateConversation(
        account: Account,
        jid: Jid,
        muc: Boolean,
        async: Boolean
    ): Conversation {
        return this.findOrCreateConversation(account, jid, muc, false, async)
    }

    fun findOrCreateConversation(
        account: Account,
        jid: Jid?,
        muc: Boolean,
        joinAfterCreate: Boolean,
        async: Boolean
    ): Conversation {
        return this.findOrCreateConversation(account, jid, muc, joinAfterCreate, null, async)
    }

    fun findOrCreateConversation(
        account: Account,
        jid: Jid?,
        muc: Boolean,
        joinAfterCreate: Boolean,
        query: MessageArchiveService.Query?,
        async: Boolean
    ): Conversation {
        synchronized(this.conversations) {
            var conversation = find(account, jid)
            if (conversation != null) {
                return conversation
            }
            conversation = databaseBackend.findConversation(account, jid)
            val loadMessagesFromDb: Boolean
            if (conversation != null) {
                conversation.status = Conversation.STATUS_AVAILABLE
                conversation.account = account
                if (muc) {
                    conversation.mode = Conversation.MODE_MULTI
                    conversation.setContactJid(jid)
                } else {
                    conversation.mode = Conversation.MODE_SINGLE
                    conversation.setContactJid(jid!!.asBareJid())
                }
                databaseBackend.updateConversation(conversation)
                loadMessagesFromDb = conversation.messagesLoaded.compareAndSet(true, false)
            } else {
                val conversationName: String
                val contact = account.roster.getContact(jid)
                if (contact != null) {
                    conversationName = contact.displayName
                } else {
                    conversationName = jid!!.local
                }
                if (muc) {
                    conversation = Conversation(
                        conversationName, account, jid,
                        Conversation.MODE_MULTI
                    )
                } else {
                    conversation = Conversation(
                        conversationName, account, jid!!.asBareJid(),
                        Conversation.MODE_SINGLE
                    )
                }
                this.databaseBackend.createConversation(conversation)
                loadMessagesFromDb = false
            }
            val c = conversation
            val runnable = Runnable {
                if (loadMessagesFromDb) {
                    c.addAll(0, databaseBackend.getMessages(c, Config.PAGE_SIZE))
                    updateConversationUi()
                    c.messagesLoaded.set(true)
                }
                if (account.xmppConnection != null
                    && !c.contact.isBlocked
                    && account.xmppConnection.features.mam()
                    && !muc
                ) {
                    if (query == null) {
                        messageArchiveService.query(c)
                    } else {
                        if (query.conversation == null) {
                            messageArchiveService.query(c, query.start, query.isCatchup)
                        }
                    }
                }
                if (joinAfterCreate) {
                    joinMuc(c)
                }
            }
            if (async) {
                mDatabaseReaderExecutor.execute(runnable)
            } else {
                runnable.run()
            }
            this.conversations.add(conversation)
            updateConversationUi()
            return conversation
        }
    }

    fun archiveConversation(conversation: Conversation) {
        archiveConversation(conversation, true)
    }

    fun archiveConversation(
        conversation: Conversation,
        maySyncronizeWithBookmarks: Boolean
    ) {
        notificationService.clear(conversation)
        conversation.status = Conversation.STATUS_ARCHIVED
        conversation.nextMessage = null
        synchronized(this.conversations) {
            messageArchiveService.kill(conversation)
            if (conversation.mode == Conversation.MODE_MULTI) {
                if (conversation.account.status == Account.State.ONLINE) {
                    val bookmark = conversation.bookmark
                    if (maySyncronizeWithBookmarks && bookmark != null && synchronizeWithBookmarks()) {
                        if (conversation.mucOptions.error == MucOptions.Error.DESTROYED) {
                            val account = bookmark.account
                            bookmark.conversation = null
                            account.bookmarks.remove(bookmark)
                            pushBookmarks(account)
                        } else if (bookmark.autojoin()) {
                            bookmark.setAutojoin(false)
                            pushBookmarks(bookmark.account)
                        }
                    }
                }
                leaveMuc(conversation)
            } else {
                if (conversation.contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                    stopPresenceUpdatesTo(conversation.contact)
                }
            }
            updateConversation(conversation)
            this.conversations.remove(conversation)
            updateConversationUi()
        }
    }

    fun stopPresenceUpdatesTo(contact: Contact) {
        Timber.d("Canceling presence request from " + contact.jid.toString())
        sendPresencePacket(contact.account, presenceGenerator.stopPresenceUpdatesTo(contact))
        contact.resetOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
    }

    fun createAccount(account: Account) {
        account.initAccountServices(this)
        databaseBackend.createAccount(account)
        this.accounts!!.add(account)
        this.reconnectAccountInBackground(account)
        updateAccountUi()
        syncEnabledAccountSetting()
        toggleForegroundService()
    }

    fun syncEnabledAccountSetting() {
        val hasEnabledAccounts = hasEnabledAccounts()
        preferences.edit().putBoolean(EventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts)
            .apply()
        toggleSetProfilePictureActivity(hasEnabledAccounts)
    }

    fun toggleSetProfilePictureActivity(enabled: Boolean) {
        try {
            val name = ComponentName(this, ChooseAccountForProfilePictureActivity::class.java)
            val targetState =
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(
                name,
                targetState,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: IllegalStateException) {
            Timber.d("unable to toggle profile picture actvitiy")
        }

    }

    fun createAccountFromKey(alias: String, callback: OnAccountCreated) {
        Thread {
            try {
                val chain = KeyChain.getCertificateChain(this, alias)
                val cert = if (chain != null && chain.size > 0) chain[0] else null
                if (cert == null) {
                    callback.informUser(R.string.unable_to_parse_certificate)
                    Thread {
                        try {
                            val chain: Array<X509Certificate>? =
                                KeyChain.getCertificateChain(this, alias);
                            val cert: X509Certificate? = chain?.getOrNull(0)
                            if (cert == null) {
                                callback.informUser(R.string.unable_to_parse_certificate);
                                return@Thread
                            }
                            val info: Pair<Jid, String>? = CryptoHelper.extractJidAndName(cert);
                            if (info == null) {
                                callback.informUser(R.string.certificate_does_not_contain_jid);
                                return@Thread
                            }
                            if (findAccountByJid(info.first) == null) {
                                val account: Account = Account(info.first, "");
                                account.setPrivateKeyAlias(alias);
                                account.setOption(Account.OPTION_DISABLED, true);
                                account.setDisplayName(info.second);
                                createAccount(account);
                                callback.onAccountCreated(account);
                                if (Config.X509_VERIFICATION) {
                                    try {
                                        memorizingTrustManager!!
                                            .getNonInteractive(account.getJid().getDomain())!!
                                            .checkClientTrusted(chain, "RSA");
                                    } catch (e: CertificateException) {
                                        callback.informUser(R.string.certificate_chain_is_not_trusted);
                                    }
                                }
                            } else {
                                callback.informUser(R.string.account_already_exists);
                            }
                        } catch (e: Exception) {
                            e.printStackTrace();
                            callback.informUser(R.string.unable_to_parse_certificate);
                        }
                    }.start()
                    return@Thread
                }
                val info = CryptoHelper.extractJidAndName(cert!!)
                if (info == null) {
                    callback.informUser(R.string.certificate_does_not_contain_jid)
                    return@Thread Thread {
                        try {
                            val chain: Array<X509Certificate>? =
                                KeyChain.getCertificateChain(this, alias);
                            val cert: X509Certificate? = chain?.getOrNull(0)
                            if (cert == null) {
                                callback.informUser(R.string.unable_to_parse_certificate);
                                return@Thread
                            }
                            val info: Pair<Jid, String>? = CryptoHelper.extractJidAndName(cert);
                            if (info == null) {
                                callback.informUser(R.string.certificate_does_not_contain_jid);
                                return@Thread
                            }
                            if (findAccountByJid(info.first) == null) {
                                val account: Account = Account(info.first, "");
                                account.setPrivateKeyAlias(alias);
                                account.setOption(Account.OPTION_DISABLED, true);
                                account.setDisplayName(info.second);
                                createAccount(account);
                                callback.onAccountCreated(account);
                                if (Config.X509_VERIFICATION) {
                                    try {
                                        memorizingTrustManager!!
                                            .getNonInteractive(account.getJid().getDomain())!!
                                            .checkClientTrusted(chain, "RSA");
                                    } catch (e: CertificateException) {
                                        callback.informUser(R.string.certificate_chain_is_not_trusted);
                                    }
                                }
                            } else {
                                callback.informUser(R.string.account_already_exists);
                            }
                        } catch (e: Exception) {
                            e.printStackTrace();
                            callback.informUser(R.string.unable_to_parse_certificate);
                        }
                    }.start()
                }
                if (findAccountByJid(info!!.first) == null) {
                    val account = Account(info.first, "")
                    account.privateKeyAlias = alias
                    account.setOption(Account.OPTION_DISABLED, true)
                    account.displayName = info.second
                    createAccount(account)
                    callback.onAccountCreated(account)
                    if (Config.X509_VERIFICATION) {
                        try {
                            memorizingTrustManager!!.getNonInteractive(account.jid.domain)
                                .checkClientTrusted(chain, "RSA")
                        } catch (e: CertificateException) {
                            callback.informUser(R.string.certificate_chain_is_not_trusted)
                        }

                    }
                } else {
                    callback.informUser(R.string.account_already_exists)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback.informUser(R.string.unable_to_parse_certificate)
            }
        }.start()

    }

    fun updateKeyInAccount(account: Account, alias: String) {
        Log.d(
            Config.LOGTAG,
            account.jid.asBareJid().toString() + ": update key in account " + alias
        )
        try {
            val chain = KeyChain.getCertificateChain(this@XmppConnectionService, alias)
            Timber.d(account.jid.asBareJid().toString() + " loaded certificate chain")
            val info = CryptoHelper.extractJidAndName(chain!![0])
            if (info == null) {
                showErrorToastInUi(R.string.certificate_does_not_contain_jid)
                return
            }
            if (account.jid.asBareJid() == info.first) {
                account.privateKeyAlias = alias
                account.displayName = info.second
                databaseBackend.updateAccount(account)
                if (Config.X509_VERIFICATION) {
                    try {
                        memorizingTrustManager!!.nonInteractive.checkClientTrusted(chain, "RSA")
                    } catch (e: CertificateException) {
                        showErrorToastInUi(R.string.certificate_chain_is_not_trusted)
                    }

                    account.axolotlService.regenerateKeys(true)
                }
            } else {
                showErrorToastInUi(R.string.jid_does_not_match_certificate)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    fun updateAccount(account: Account): Boolean {
        if (databaseBackend.updateAccount(account)) {
            account.setShowErrorNotification(true)
            this.statusListener.onStatusChanged(account)
            databaseBackend.updateAccount(account)
            reconnectAccountInBackground(account)
            updateAccountUi()
            notificationService.updateErrorNotification()
            toggleForegroundService()
            syncEnabledAccountSetting()
            return true
        } else {
            return false
        }
    }

    fun updateAccountPasswordOnServer(
        account: Account,
        newPassword: String,
        callback: OnAccountPasswordChanged
    ) {
        val iq = iqGenerator.generateSetPassword(account, newPassword)
        sendIqPacket(account, iq, OnIqPacketReceived { a, packet ->
            if (packet.getType() == IqPacket.TYPE.RESULT) {
                a.setPassword(newPassword)
                a.setOption(Account.OPTION_MAGIC_CREATE, false)
                databaseBackend.updateAccount(a)
                callback.onPasswordChangeSucceeded()
            } else {
                callback.onPasswordChangeFailed()
            }
        })
    }

    fun deleteAccount(account: Account) {
        synchronized(this.conversations) {
            for (conversation in conversations) {
                if (conversation.account === account) {
                    if (conversation.mode == Conversation.MODE_MULTI) {
                        leaveMuc(conversation)
                    }
                    conversations.remove(conversation)
                }
            }
            if (account.xmppConnection != null) {
                Thread { disconnect(account, true) }.start()
            }
            val runnable = {
                if (!databaseBackend.deleteAccount(account)) {
                    Log.d(
                        Config.LOGTAG,
                        account.jid.asBareJid().toString() + ": unable to delete account"
                    )
                }
            }
            mDatabaseWriterExecutor.execute(runnable)
            this.accounts!!.remove(account)
            this.mRosterSyncTaskManager.clear(account)
            updateAccountUi()
            notificationService.updateErrorNotification()
            syncEnabledAccountSetting()
            toggleForegroundService()
        }
    }

    fun setOnConversationListChangedListener(listener: OnConversationUpdate) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!this.mOnConversationUpdates.add(listener)) {
                Log.w(
                    Config.LOGTAG,
                    listener.javaClass.name + " is already registered as ConversationListChangedListener"
                )
            }
            this.notificationService.setIsInForeground(this.mOnConversationUpdates.size > 0)
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }

    fun removeOnConversationListChangedListener(listener: OnConversationUpdate) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            this.mOnConversationUpdates.remove(listener)
            this.notificationService.setIsInForeground(this.mOnConversationUpdates.size > 0)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }

    fun setOnShowErrorToastListener(listener: OnShowErrorToast) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!this.mOnShowErrorToasts.add(listener)) {
                Log.w(
                    Config.LOGTAG,
                    listener.javaClass.name + " is already registered as OnShowErrorToastListener"
                )
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }

    fun removeOnShowErrorToastListener(onShowErrorToast: OnShowErrorToast) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            this.mOnShowErrorToasts.remove(onShowErrorToast)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }

    fun setOnAccountListChangedListener(listener: OnAccountUpdate) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!this.mOnAccountUpdates.add(listener)) {
                Log.w(
                    Config.LOGTAG,
                    listener.javaClass.name + " is already registered as OnAccountListChangedtListener"
                )
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }

    fun removeOnAccountListChangedListener(listener: OnAccountUpdate) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            this.mOnAccountUpdates.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }

    fun setOnCaptchaRequestedListener(listener: OnCaptchaRequested) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!this.mOnCaptchaRequested.add(listener)) {
                Log.w(
                    Config.LOGTAG,
                    listener.javaClass.name + " is already registered as OnCaptchaRequestListener"
                )
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }

    fun removeOnCaptchaRequestedListener(listener: OnCaptchaRequested) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            this.mOnCaptchaRequested.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }

    fun setOnRosterUpdateListener(listener: OnRosterUpdate) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!this.mOnRosterUpdates.add(listener)) {
                Log.w(
                    Config.LOGTAG,
                    listener.javaClass.name + " is already registered as OnRosterUpdateListener"
                )
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }

    fun removeOnRosterUpdateListener(listener: OnRosterUpdate) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            this.mOnRosterUpdates.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }

    fun setOnUpdateBlocklistListener(listener: OnUpdateBlocklist) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!this.mOnUpdateBlocklist.add(listener)) {
                Log.w(
                    Config.LOGTAG,
                    listener.javaClass.name + " is already registered as OnUpdateBlocklistListener"
                )
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }

    fun removeOnUpdateBlocklistListener(listener: OnUpdateBlocklist) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            this.mOnUpdateBlocklist.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }

    fun setOnKeyStatusUpdatedListener(listener: OnKeyStatusUpdated) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!this.mOnKeyStatusUpdated.add(listener)) {
                Log.w(
                    Config.LOGTAG,
                    listener.javaClass.name + " is already registered as OnKeyStatusUpdateListener"
                )
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }

    fun removeOnNewKeysAvailableListener(listener: OnKeyStatusUpdated) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            this.mOnKeyStatusUpdated.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }

    fun setOnMucRosterUpdateListener(listener: OnMucRosterUpdate) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!this.mOnMucRosterUpdate.add(listener)) {
                Log.w(
                    Config.LOGTAG,
                    listener.javaClass.name + " is already registered as OnMucRosterListener"
                )
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }

    fun removeOnMucRosterUpdateListener(listener: OnMucRosterUpdate) {
        val remainingListeners: Boolean
        synchronized(LISTENER_LOCK) {
            this.mOnMucRosterUpdate.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }

    fun checkListeners(): Boolean {
        return (this.mOnAccountUpdates.size == 0
                && this.mOnConversationUpdates.size == 0
                && this.mOnRosterUpdates.size == 0
                && this.mOnCaptchaRequested.size == 0
                && this.mOnMucRosterUpdate.size == 0
                && this.mOnUpdateBlocklist.size == 0
                && this.mOnShowErrorToasts.size == 0
                && this.mOnKeyStatusUpdated.size == 0)
    }

    fun switchToForeground() {
        val broadcastLastActivity = broadcastLastActivity()
        for (conversation in getConversations()) {
            if (conversation.mode == Conversation.MODE_MULTI) {
                conversation.mucOptions.resetChatState()
            } else {
                conversation.incomingChatState = Config.DEFAULT_CHATSTATE
            }
        }
        for (account in accounts!!) {
            if (account.status == Account.State.ONLINE) {
                account.deactivateGracePeriod()
                val connection = account.xmppConnection
                if (connection != null) {
                    if (connection.features.csi()) {
                        connection.sendActive()
                    }
                    if (broadcastLastActivity) {
                        sendPresence(
                            account,
                            false
                        ) //send new presence but don't include idle because we are not
                    }
                }
            }
        }
        Timber.d("app switched into foreground")
    }

    fun switchToBackground() {
        val broadcastLastActivity = broadcastLastActivity()
        if (broadcastLastActivity) {
            mLastActivity = System.currentTimeMillis()
            val editor = preferences.edit()
            editor.putLong(SETTING_LAST_ACTIVITY_TS, mLastActivity)
            editor.apply()
        }
        for (account in accounts!!) {
            if (account.status == Account.State.ONLINE) {
                val connection = account.xmppConnection
                if (connection != null) {
                    if (broadcastLastActivity) {
                        sendPresence(account, true)
                    }
                    if (connection.features.csi()) {
                        connection.sendInactive()
                    }
                }
            }
        }
        this.notificationService.setIsInForeground(false)
        Timber.d("app switched into background")
    }

    fun connectMultiModeConversations(account: Account) {
        val conversations = getConversations()
        for (conversation in conversations) {
            if (conversation.mode == Conversation.MODE_MULTI && conversation.account === account) {
                joinMuc(conversation)
            }
        }
    }

    fun joinMuc(conversation: Conversation) {
        joinMuc(conversation, null, false)
    }

    fun joinMuc(conversation: Conversation, followedInvite: Boolean) {
        joinMuc(conversation, null, followedInvite)
    }

    fun joinMuc(
        conversation: Conversation,
        onConferenceJoined: ((Conversation) -> Unit)?,
        followedInvite: Boolean = false
    ) {
        val account = conversation.account
        account.pendingConferenceJoins.remove(conversation)
        account.pendingConferenceLeaves.remove(conversation)
        if (account.status == Account.State.ONLINE) {
            sendPresencePacket(account, presenceGenerator.leave(conversation.mucOptions))
            conversation.resetMucOptions()
            if (onConferenceJoined != null) {
                conversation.mucOptions.flagNoAutoPushConfiguration()
            }
            conversation.setHasMessagesLeftOnServer(false)
            fetchConferenceConfiguration(conversation, object : OnConferenceConfigurationFetched {

                fun join(conversation: Conversation) {
                    val account = conversation.account
                    val mucOptions = conversation.mucOptions

                    if (mucOptions.nonanonymous() && !mucOptions.membersOnly() && !conversation.getBooleanAttribute(
                            "accept_non_anonymous",
                            false
                        )
                    ) {
                        mucOptions.error = MucOptions.Error.NON_ANONYMOUS
                        updateConversationUi()
                        onConferenceJoined?.invoke(conversation)
                        return
                    }

                    val joinJid = mucOptions.self.fullJid
                    Log.d(
                        Config.LOGTAG,
                        account.jid.asBareJid().toString() + ": joining conversation " + joinJid.toString()
                    )
                    val packet = presenceGenerator.selfPresence(
                        account,
                        Presence.Status.ONLINE,
                        mucOptions.nonanonymous() || onConferenceJoined != null
                    )
                    packet.to = joinJid
                    val x = packet.addChild("x", "http://jabber.org/protocol/muc")
                    if (conversation.mucOptions.password != null) {
                        x.addChild("password").content = mucOptions.password
                    }

                    if (mucOptions.mamSupport()) {
                        // Use MAM instead of the limited muc history to get history
                        x.addChild("history").setAttribute("maxchars", "0")
                    } else {
                        // Fallback to muc history
                        x.addChild("history").setAttribute(
                            "since",
                            PresenceGenerator.getTimestamp(conversation.lastMessageTransmitted.timestamp)
                        )
                    }
                    sendPresencePacket(account, packet)
                    onConferenceJoined?.invoke(conversation)
                    if (joinJid != conversation.jid) {
                        conversation.setContactJid(joinJid)
                        databaseBackend.updateConversation(conversation)
                    }

                    if (mucOptions.mamSupport()) {
                        messageArchiveService.catchupMUC(conversation)
                    }
                    if (mucOptions.isPrivateAndNonAnonymous) {
                        fetchConferenceMembers(conversation)
                        if (followedInvite && conversation.bookmark == null) {
                            saveConversationAsBookmark(conversation, null)
                        }
                    }
                    sendUnsentMessages(conversation)
                }

                override fun onConferenceConfigurationFetched(conversation: Conversation) {
                    if (conversation.status == Conversation.STATUS_ARCHIVED) {
                        Log.d(
                            Config.LOGTAG,
                            account.jid.asBareJid().toString() + ": conversation (" + conversation.jid + ") got archived before IQ result"
                        )
                        return
                    }
                    join(conversation)
                }

                override fun onFetchFailed(conversation: Conversation, error: Element?) {
                    if (conversation.status == Conversation.STATUS_ARCHIVED) {
                        Log.d(
                            Config.LOGTAG,
                            account.jid.asBareJid().toString() + ": conversation (" + conversation.jid + ") got archived before IQ result"
                        )
                        return
                    }
                    if (error != null && "remote-server-not-found" == error.name) {
                        conversation.mucOptions.error = MucOptions.Error.SERVER_NOT_FOUND
                        updateConversationUi()
                    } else {
                        join(conversation)
                        fetchConferenceConfiguration(conversation)
                    }
                }
            })
            updateConversationUi()
        } else {
            account.pendingConferenceJoins.add(conversation)
            conversation.resetMucOptions()
            conversation.setHasMessagesLeftOnServer(false)
            updateConversationUi()
        }
    }

    fun fetchConferenceMembers(conversation: Conversation) {
        val account = conversation.account
        val axolotlService = account.axolotlService
        val affiliations = arrayOf("member", "admin", "owner")
        val callback = object : OnIqPacketReceived {

            var i = 0
            var success = true

            override fun onIqPacketReceived(account: Account, packet: IqPacket) {
                val omemoEnabled = conversation.nextEncryption == Message.ENCRYPTION_AXOLOTL
                val query = packet.query("http://jabber.org/protocol/muc#admin")
                if (packet.type == IqPacket.TYPE.RESULT && query != null) {
                    for (child in query.children) {
                        if ("item" == child.name) {
                            val user = AbstractParser.parseItem(conversation, child)
                            if (!user.realJidMatchesAccount()) {
                                val isNew = conversation.mucOptions.updateUser(user)
                                val contact = user.contact
                                if (omemoEnabled
                                    && isNew
                                    && user.realJid != null
                                    && (contact == null || !contact.mutualPresenceSubscription())
                                    && axolotlService.hasEmptyDeviceList(user.realJid)
                                ) {
                                    axolotlService.fetchDeviceIds(user.realJid)
                                }
                            }
                        }
                    }
                } else {
                    success = false
                    Log.d(
                        Config.LOGTAG,
                        account.jid.asBareJid().toString() + ": could not request affiliation " + affiliations[i] + " in " + conversation.jid.asBareJid()
                    )
                }
                ++i
                if (i >= affiliations.size) {
                    val members = conversation.mucOptions.getMembers(true)
                    if (success) {
                        val cryptoTargets = conversation.acceptedCryptoTargets
                        var changed = false
                        val iterator = cryptoTargets.listIterator()
                        while (iterator.hasNext()) {
                            val jid = iterator.next()
                            if (!members.contains(jid) && !members.contains(Jid.ofDomain(jid.domain))) {
                                iterator.remove()
                                Log.d(
                                    Config.LOGTAG,
                                    account.jid.asBareJid().toString() + ": removed " + jid + " from crypto targets of " + conversation.name
                                )
                                changed = true
                            }
                        }
                        if (changed) {
                            conversation.acceptedCryptoTargets = cryptoTargets
                            updateConversation(conversation)
                        }
                    }
                    avatarService.clear(conversation)
                    updateMucRosterUi()
                    updateConversationUi()
                }
            }
        }
        for (affiliation in affiliations) {
            sendIqPacket(account, iqGenerator.queryAffiliation(conversation, affiliation), callback)
        }
        Log.d(
            Config.LOGTAG,
            account.jid.asBareJid().toString() + ": fetching members for " + conversation.name
        )
    }

    fun providePasswordForMuc(conversation: Conversation, password: String) {
        if (conversation.mode == Conversation.MODE_MULTI) {
            conversation.mucOptions.password = password
            if (conversation.bookmark != null) {
                if (synchronizeWithBookmarks()) {
                    conversation.bookmark.setAutojoin(true)
                }
                pushBookmarks(conversation.account)
            }
            updateConversation(conversation)
            joinMuc(conversation)
        }
    }

    fun hasEnabledAccounts(): Boolean {
        if (this.accounts == null) {
            return false
        }
        for (account in this.accounts!!) {
            if (account.isEnabled) {
                return true
            }
        }
        return false
    }


    fun getAttachments(conversation: Conversation, limit: Int, onMediaLoaded: OnMediaLoaded) {
        getAttachments(conversation.account, conversation.jid.asBareJid(), limit, onMediaLoaded)
    }

    fun getAttachments(account: Account, jid: Jid, limit: Int, onMediaLoaded: OnMediaLoaded) {
        getAttachments(account.uuid, jid.asBareJid(), limit, onMediaLoaded)
    }


    fun getAttachments(account: String, jid: Jid, limit: Int, onMediaLoaded: OnMediaLoaded) {
        Thread {
            onMediaLoaded.onMediaLoaded(
                fileBackend.convertToAttachments(
                    databaseBackend.getRelativeFilePaths(
                        account,
                        jid,
                        limit
                    )
                )
            )
        }.start()
    }

    fun persistSelfNick(self: MucOptions.User) {
        val conversation = self.conversation
        val tookProposedNickFromBookmark = conversation.mucOptions.isTookProposedNickFromBookmark
        val full = self.fullJid
        if (full != conversation.jid) {
            Timber.d("nick changed. updating")
            conversation.setContactJid(full)
            databaseBackend.updateConversation(conversation)
        }

        val bookmark = conversation.bookmark
        val bookmarkedNick = bookmark?.nick
        if (bookmark != null && (tookProposedNickFromBookmark || TextUtils.isEmpty(bookmarkedNick)) && full.resource != bookmarkedNick) {
            Log.d(
                Config.LOGTAG,
                conversation.account.jid.asBareJid().toString() + ": persist nick '" + full.resource + "' into bookmark for " + conversation.jid.asBareJid()
            )
            bookmark.nick = full.resource
            pushBookmarks(bookmark.account)
        }
    }

    fun renameInMuc(
        conversation: Conversation,
        nick: String,
        callback: UiCallback<Conversation>
    ): Boolean {
        val options = conversation.mucOptions
        val joinJid = options.createJoinJid(nick) ?: return false
        if (options.online()) {
            val account = conversation.account
            options.setOnRenameListener(object : OnRenameListener {

                override fun onSuccess() {
                    callback.success(conversation)
                }

                override fun onFailure() {
                    callback.error(R.string.nick_in_use, conversation)
                }
            })

            val packet = PresencePacket()
            packet.to = joinJid
            packet.from = conversation.account.jid

            val sig = account.pgpSignature
            if (sig != null) {
                packet.addChild("status").content = "online"
                packet.addChild("x", "jabber:x:signed").content = sig
            }
            sendPresencePacket(account, packet)
        } else {
            conversation.setContactJid(joinJid)
            databaseBackend.updateConversation(conversation)
            if (conversation.account.status == Account.State.ONLINE) {
                val bookmark = conversation.bookmark
                if (bookmark != null) {
                    bookmark.nick = nick
                    pushBookmarks(bookmark.account)
                }
                joinMuc(conversation)
            }
        }
        return true
    }

    fun leaveMuc(conversation: Conversation) {
        leaveMuc(conversation, false)
    }

    fun leaveMuc(conversation: Conversation, now: Boolean) {
        val account = conversation.account
        account.pendingConferenceJoins.remove(conversation)
        account.pendingConferenceLeaves.remove(conversation)
        if (account.status == Account.State.ONLINE || now) {
            sendPresencePacket(
                conversation.account,
                presenceGenerator.leave(conversation.mucOptions)
            )
            conversation.mucOptions.setOffline()
            val bookmark = conversation.bookmark
            if (bookmark != null) {
                bookmark.conversation = null
            }
            Log.d(
                Config.LOGTAG,
                conversation.account.jid.asBareJid().toString() + ": leaving muc " + conversation.jid
            )
        } else {
            account.pendingConferenceLeaves.add(conversation)
        }
    }

    fun findConferenceServer(account: Account): String? {
        var server: String?
        if (account.xmppConnection != null) {
            server = account.xmppConnection.mucServer
            if (server != null) {
                return server
            }
        }
        for (other in accounts!!) {
            if (other !== account && other.xmppConnection != null) {
                server = other.xmppConnection.mucServer
                if (server != null) {
                    return server
                }
            }
        }
        return null
    }


    fun createPublicChannel(
        account: Account,
        name: String,
        address: Jid,
        callback: UiCallback<Conversation>
    ) {
        joinMuc(findOrCreateConversation(account, address, true, false, true), { conversation ->
            val configuration = IqGenerator.defaultChannelConfiguration()
            if (!TextUtils.isEmpty(name)) {
                configuration.putString("muc#roomconfig_roomname", name)
            }
            pushConferenceConfiguration(
                conversation,
                configuration,
                object : OnConfigurationPushed {
                    override fun onPushSucceeded() {
                        saveConversationAsBookmark(conversation, name)
                        callback.success(conversation)
                    }

                    override fun onPushFailed() {
                        if (conversation.getMucOptions().getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                            callback.error(
                                R.string.unable_to_set_channel_configuration,
                                conversation
                            )
                        } else {
                            callback.error(R.string.joined_an_existing_channel, conversation)
                        }
                    }
                })
        })
    }

    fun createAdhocConference(
        account: Account,
        name: String?,
        jids: Iterable<Jid>,
        callback: UiCallback<Conversation>?
    ): Boolean {
        Log.d(
            Config.LOGTAG,
            account.jid.asBareJid().toString() + ": creating adhoc conference with " + jids.toString()
        )
        if (account.status == Account.State.ONLINE) {
            try {
                val server = findConferenceServer(account)
                if (server == null) {
                    callback?.error(R.string.no_conference_server_found, null)
                    return false
                }
                val jid = Jid.of(CryptoHelper.pronounceable(rng!!), server, null)
                val conversation = findOrCreateConversation(account, jid, true, false, true)
                joinMuc(conversation, object : OnConferenceJoined {
                    override fun invoke(conversation: Conversation) {
                        val configuration = IqGenerator.defaultGroupChatConfiguration()
                        if (!TextUtils.isEmpty(name)) {
                            configuration.putString("muc#roomconfig_roomname", name)
                        }
                        pushConferenceConfiguration(
                            conversation,
                            configuration,
                            object : OnConfigurationPushed {
                                override fun onPushSucceeded() {
                                    for (invite in jids) {
                                        invite(conversation, invite)
                                    }
                                    if (account.countPresences() > 1) {
                                        directInvite(conversation, account.jid.asBareJid())
                                    }
                                    saveConversationAsBookmark(conversation, name)
                                    callback?.success(conversation)
                                }

                                override fun onPushFailed() {
                                    archiveConversation(conversation)
                                    callback?.error(
                                        R.string.conference_creation_failed,
                                        conversation
                                    )
                                }
                            })
                    }
                })
                return true
            } catch (e: IllegalArgumentException) {
                callback?.error(R.string.conference_creation_failed, null)
                return false
            }

        } else {
            callback?.error(R.string.not_connected_try_again, null)
            return false
        }
    }

    @JvmOverloads
    fun fetchConferenceConfiguration(
        conversation: Conversation,
        callback: OnConferenceConfigurationFetched? = null
    ) {
        val request = IqPacket(IqPacket.TYPE.GET)
        request.to = conversation.jid.asBareJid()
        request.query("http://jabber.org/protocol/disco#info")
        sendIqPacket(conversation.account, request, OnIqPacketReceived { account, packet ->
            if (packet.type == IqPacket.TYPE.RESULT) {

                val mucOptions = conversation.mucOptions
                val bookmark = conversation.bookmark
                val sameBefore = StringUtils.equals(bookmark?.bookmarkName, mucOptions.name)

                if (mucOptions.updateConfiguration(ServiceDiscoveryResult(packet))) {
                    Log.d(
                        Config.LOGTAG,
                        account.jid.asBareJid().toString() + ": muc configuration changed for " + conversation.jid.asBareJid()
                    )
                    updateConversation(conversation)
                }

                if (bookmark != null && (sameBefore || bookmark.bookmarkName == null)) {
                    if (bookmark.setBookmarkName(StringUtils.nullOnEmpty(mucOptions.name))) {
                        pushBookmarks(account)
                    }
                }


                callback?.onConferenceConfigurationFetched(conversation)



                updateConversationUi()
            } else if (packet.type == IqPacket.TYPE.ERROR) {
                callback?.onFetchFailed(conversation, packet.error)
            }
        })
    }

    fun pushNodeConfiguration(
        account: Account,
        node: String,
        options: Bundle?,
        callback: OnConfigurationPushed
    ) {
        pushNodeConfiguration(account, account.jid.asBareJid(), node, options, callback)
    }

    fun pushNodeConfiguration(
        account: Account,
        jid: Jid,
        node: String,
        options: Bundle?,
        callback: OnConfigurationPushed?
    ) {
        Timber.d("pushing node configuration")
        sendIqPacket(
            account,
            iqGenerator.requestPubsubConfiguration(jid, node),
            OnIqPacketReceived { account, packet ->
                if (packet.type == IqPacket.TYPE.RESULT) {
                    val pubsub =
                        packet.findChild("pubsub", "http://jabber.org/protocol/pubsub#owner")
                    val configuration = pubsub?.findChild("configure")
                    val x = configuration?.findChild("x", Namespace.DATA)
                    if (x != null) {
                        val data = Data.parse(x)
                        data.submit(options)
                        sendIqPacket(
                            account,
                            iqGenerator.publishPubsubConfiguration(jid, node, data),
                            OnIqPacketReceived { account, packet ->
                                if (packet.type == IqPacket.TYPE.RESULT && callback != null) {
                                    Log.d(
                                        Config.LOGTAG,
                                        account.jid.asBareJid().toString() + ": successfully changed node configuration for node " + node
                                    )
                                    callback.onPushSucceeded()
                                } else if (packet.type == IqPacket.TYPE.ERROR && callback != null) {
                                    callback.onPushFailed()
                                }
                            })
                    } else callback?.onPushFailed()
                } else if (packet.type == IqPacket.TYPE.ERROR && callback != null) {
                    callback.onPushFailed()
                }
            })
    }

    fun pushConferenceConfiguration(
        conversation: Conversation,
        options: Bundle,
        callback: OnConfigurationPushed?
    ) {
        if (options.getString("muc#roomconfig_whois", "moderators") == "anyone") {
            conversation.setAttribute("accept_non_anonymous", true)
            updateConversation(conversation)
        }
        val request = IqPacket(IqPacket.TYPE.GET)
        request.to = conversation.jid.asBareJid()
        request.query("http://jabber.org/protocol/muc#owner")
        sendIqPacket(conversation.account, request, OnIqPacketReceived { account, packet ->
            if (packet.type == IqPacket.TYPE.RESULT) {
                val data = Data.parse(packet.query().findChild("x", Namespace.DATA)!!)
                data.submit(options)
                Timber.d(data.toString())
                val set = IqPacket(IqPacket.TYPE.SET)
                set.to = conversation.jid.asBareJid()
                set.query("http://jabber.org/protocol/muc#owner").addChild(data)
                sendIqPacket(account, set, OnIqPacketReceived { account, packet ->
                    if (callback != null) {
                        if (packet.type == IqPacket.TYPE.RESULT) {
                            callback.onPushSucceeded()
                        } else {
                            callback.onPushFailed()
                        }
                    }
                })
            } else {
                callback?.onPushFailed()
            }
        })
    }

    fun pushSubjectToConference(conference: Conversation, subject: String) {
        val packet =
            this.messageGenerator.conferenceSubject(conference, StringUtils.nullOnEmpty(subject))
        this.sendMessagePacket(conference.account, packet)
    }

    fun changeAffiliationInConference(
        conference: Conversation,
        user: Jid,
        affiliation: MucOptions.Affiliation,
        callback: OnAffiliationChanged
    ) {
        val jid = user.asBareJid()
        val request = this.iqGenerator.changeAffiliation(conference, jid, affiliation.toString())
        sendIqPacket(conference.account, request, OnIqPacketReceived { account, packet ->
            if (packet.type == IqPacket.TYPE.RESULT) {
                conference.mucOptions.changeAffiliation(jid, affiliation)
                avatarService.clear(conference)
                callback.onAffiliationChangedSuccessful(jid)
            } else {
                callback.onAffiliationChangeFailed(jid, R.string.could_not_change_affiliation)
            }
        })
    }

    fun changeAffiliationsInConference(
        conference: Conversation,
        before: MucOptions.Affiliation,
        after: MucOptions.Affiliation
    ) {
        val jids = ArrayList<Jid>()
        for (user in conference.mucOptions.users) {
            if (user.affiliation == before && user.realJid != null) {
                jids.add(user.realJid)
            }
        }
        val request = this.iqGenerator.changeAffiliation(conference, jids, after.toString())
        sendIqPacket(conference.account, request, mDefaultIqHandler)
    }

    fun changeRoleInConference(conference: Conversation, nick: String, role: MucOptions.Role) {
        val request = this.iqGenerator.changeRole(conference, nick, role.toString())
        Timber.d(request.toString())
        sendIqPacket(conference.account, request, OnIqPacketReceived { account, packet ->
            if (packet.getType() != IqPacket.TYPE.RESULT) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() + " unable to change role of " + nick
                )
            }
        })
    }

    fun destroyRoom(conversation: Conversation, callback: OnRoomDestroy?) {
        val request = IqPacket(IqPacket.TYPE.SET)
        request.to = conversation.jid.asBareJid()
        request.query("http://jabber.org/protocol/muc#owner").addChild("destroy")
        sendIqPacket(conversation.account, request, OnIqPacketReceived { account, packet ->
            if (packet.type == IqPacket.TYPE.RESULT) {
                callback?.onRoomDestroySucceeded()
            } else if (packet.type == IqPacket.TYPE.ERROR) {
                callback?.onRoomDestroyFailed()
            }
        })
    }

    fun disconnect(account: Account, force: Boolean) {
        if (account.status == Account.State.ONLINE || account.status == Account.State.DISABLED) {
            val connection = account.xmppConnection
            if (!force) {
                val conversations = getConversations()
                for (conversation in conversations) {
                    if (conversation.account === account) {
                        if (conversation.mode == Conversation.MODE_MULTI) {
                            leaveMuc(conversation, true)
                        }
                    }
                }
                sendOfflinePresence(account)
            }
            connection.disconnect(force)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }

    @JvmOverloads
    fun updateMessage(message: Message, includeBody: Boolean = true) {
        databaseBackend.updateMessage(message, includeBody)
        updateConversationUi()
    }

    fun updateMessage(message: Message, uuid: String) {
        if (!databaseBackend.updateMessage(message, uuid)) {
            Log.e(Config.LOGTAG, "error updated message in DB after edit")
        }
        updateConversationUi()
    }

    fun syncDirtyContacts(account: Account) {
        for (contact in account.roster.contacts) {
            if (contact.getOption(Contact.Options.DIRTY_PUSH)) {
                pushContactToServer(contact)
            }
            if (contact.getOption(Contact.Options.DIRTY_DELETE)) {
                deleteContactOnServer(contact)
            }
        }
    }

    fun createContact(contact: Contact, autoGrant: Boolean) {
        if (autoGrant) {
            contact.setOption(Contact.Options.PREEMPTIVE_GRANT)
            contact.setOption(Contact.Options.ASKING)
        }
        pushContactToServer(contact)
    }

    fun pushContactToServer(contact: Contact) {
        contact.resetOption(Contact.Options.DIRTY_DELETE)
        contact.setOption(Contact.Options.DIRTY_PUSH)
        val account = contact.account
        if (account.status == Account.State.ONLINE) {
            val ask = contact.getOption(Contact.Options.ASKING)
            val sendUpdates = contact
                .getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST) && contact.getOption(
                Contact.Options.PREEMPTIVE_GRANT
            )
            val iq = IqPacket(IqPacket.TYPE.SET)
            iq.query(Namespace.ROSTER).addChild(contact.asElement())
            account.xmppConnection.sendIqPacket(iq, mDefaultIqHandler)
            if (sendUpdates) {
                sendPresencePacket(account, presenceGenerator.sendPresenceUpdatesTo(contact))
            }
            if (ask) {
                sendPresencePacket(account, presenceGenerator.requestPresenceUpdatesFrom(contact))
            }
        } else {
            syncRoster(contact.account)
        }
    }

    fun publishMucAvatar(conversation: Conversation, image: Uri, callback: OnAvatarPublication) {
        Thread {
            val format = Config.AVATAR_FORMAT
            val size = Config.AVATAR_SIZE
            val avatar = fileBackend.getPepAvatar(image, size, format)
            if (avatar != null) {
                if (!fileBackend.save(avatar)) {
                    callback.onAvatarPublicationFailed(R.string.error_saving_avatar)
                    Thread {
                        val format: Bitmap.CompressFormat = Config.AVATAR_FORMAT;
                        val size: Int = Config.AVATAR_SIZE;
                        val avatar: Avatar = fileBackend.getPepAvatar(image, size, format);
                        if (avatar != null) {
                            if (!fileBackend.save(avatar)) {
                                callback.onAvatarPublicationFailed(R.string.error_saving_avatar);
                            } else {
                                avatar.owner = conversation.jid.asBareJid();
                                publishMucAvatar(conversation, avatar, callback);
                            }
                        } else {
                            callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting);
                        }
                    }.start()
                } else {
                    avatar.owner = conversation.jid.asBareJid()
                    publishMucAvatar(conversation, avatar, callback)
                }
            } else {
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting)
            }
        }.start()
    }

    fun publishAvatar(account: Account, image: Uri, callback: OnAvatarPublication) {
        Thread {
            val format = Config.AVATAR_FORMAT
            val size = Config.AVATAR_SIZE
            val avatar = fileBackend.getPepAvatar(image, size, format)
            if (avatar != null) {
                if (!fileBackend.save(avatar)) {
                    Timber.d("unable to save vcard")
                    callback.onAvatarPublicationFailed(R.string.error_saving_avatar)
                    Thread {
                        val format: Bitmap.CompressFormat = Config.AVATAR_FORMAT;
                        val size: Int = Config.AVATAR_SIZE;
                        val avatar: Avatar? = fileBackend.getPepAvatar(image, size, format);
                        if (avatar != null) {
                            if (!fileBackend.save(avatar)) {
                                Timber.d("unable to save vcard");
                                callback.onAvatarPublicationFailed(R.string.error_saving_avatar);
                            } else
                                publishAvatar(account, avatar, callback);
                        } else {
                            callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting);
                        }
                    }.start()
                } else
                    publishAvatar(account, avatar, callback)
            } else {
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting)
            }
        }.start()

    }

    fun publishMucAvatar(
        conversation: Conversation,
        avatar: Avatar,
        callback: OnAvatarPublication
    ) {
        val retrieve = iqGenerator.retrieveVcardAvatar(avatar)
        sendIqPacket(conversation.account, retrieve, OnIqPacketReceived { account, response ->
            val itemNotFound =
                response.getType() == IqPacket.TYPE.ERROR && response.hasChild("error") && response.findChild(
                    "error"
                )!!.hasChild("item-not-found")
            if (response.getType() == IqPacket.TYPE.RESULT || itemNotFound) {
                var vcard = response.findChild("vCard", "vcard-temp")
                if (vcard == null) {
                    vcard = Element("vCard", "vcard-temp")
                }
                var photo = vcard!!.findChild("PHOTO")
                if (photo == null) {
                    photo = vcard!!.addChild("PHOTO")
                }
                photo!!.clearChildren()
                photo!!.addChild("TYPE").setContent(avatar.type)
                photo!!.addChild("BINVAL").setContent(avatar.image)
                val publication = IqPacket(IqPacket.TYPE.SET)
                publication.to = conversation.jid.asBareJid()
                publication.addChild(vcard)
                sendIqPacket(account, publication, OnIqPacketReceived { a1, publicationResponse ->
                    if (publicationResponse.getType() == IqPacket.TYPE.RESULT) {
                        callback.onAvatarPublicationSucceeded()
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            "failed to publish vcard " + publicationResponse.getError()!!
                        )
                        callback.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject)
                    }
                })
            } else {
                Timber.d("failed to request vcard " + response.toString())
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_no_server_support)
            }
        })
    }

    fun publishAvatar(account: Account, avatar: Avatar?, callback: OnAvatarPublication?) {
        val options: Bundle?
        if (account.xmppConnection.features.pepPublishOptions()) {
            options = PublishOptions.openAccess()
        } else {
            options = null
        }
        publishAvatar(account, avatar, options, true, callback)
    }

    fun publishAvatar(
        account: Account,
        avatar: Avatar?,
        options: Bundle?,
        retry: Boolean,
        callback: OnAvatarPublication?
    ) {
        Log.d(
            Config.LOGTAG,
            account.jid.asBareJid().toString() + ": publishing avatar. options=" + options
        )
        val packet = this.iqGenerator.publishAvatar(avatar, options)
        this.sendIqPacket(account, packet, OnIqPacketReceived { account, result ->
            if (result.type == IqPacket.TYPE.RESULT) {
                publishAvatarMetadata(account, avatar, options, true, callback)
            } else if (retry && PublishOptions.preconditionNotMet(result)) {
                pushNodeConfiguration(
                    account,
                    "urn:xmpp:avatar:data",
                    options,
                    object : OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            Log.d(
                                Config.LOGTAG,
                                account.jid.asBareJid().toString() + ": changed node configuration for avatar node"
                            )
                            publishAvatar(account, avatar, options, false, callback)
                        }

                        override fun onPushFailed() {
                            Log.d(
                                Config.LOGTAG,
                                account.jid.asBareJid().toString() + ": unable to change node configuration for avatar node"
                            )
                            publishAvatar(account, avatar, null, false, callback)
                        }
                    })
            } else {
                val error = result.findChild("error")
                Log.d(
                    Config.LOGTAG,
                    account.jid.asBareJid().toString() + ": server rejected avatar " + avatar!!.size / 1024 + "KiB " + (error?.toString()
                        ?: "")
                )
                callback?.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject)
            }
        })
    }

    fun publishAvatarMetadata(
        account: Account,
        avatar: Avatar?,
        options: Bundle?,
        retry: Boolean,
        callback: OnAvatarPublication?
    ) {
        val packet = this@XmppConnectionService.iqGenerator.publishAvatarMetadata(avatar, options)
        sendIqPacket(account, packet, OnIqPacketReceived { account, result ->
            if (result.type == IqPacket.TYPE.RESULT) {
                if (account.setAvatar(avatar!!.filename)) {
                    avatarService.clear(account)
                    databaseBackend.updateAccount(account)
                    notifyAccountAvatarHasChanged(account)
                }
                Log.d(
                    Config.LOGTAG,
                    account.jid.asBareJid().toString() + ": published avatar " + avatar.size / 1024 + "KiB"
                )
                callback?.onAvatarPublicationSucceeded()
            } else if (retry && PublishOptions.preconditionNotMet(result)) {
                pushNodeConfiguration(
                    account,
                    "urn:xmpp:avatar:metadata",
                    options,
                    object : OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            Log.d(
                                Config.LOGTAG,
                                account.jid.asBareJid().toString() + ": changed node configuration for avatar meta data node"
                            )
                            publishAvatarMetadata(account, avatar, options, false, callback)
                        }

                        override fun onPushFailed() {
                            Log.d(
                                Config.LOGTAG,
                                account.jid.asBareJid().toString() + ": unable to change node configuration for avatar meta data node"
                            )
                            publishAvatarMetadata(account, avatar, null, false, callback)
                        }
                    })
            } else {
                callback?.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject)
            }
        })
    }

    fun republishAvatarIfNeeded(account: Account) {
        if (account.axolotlService.isPepBroken) {
            Log.d(
                Config.LOGTAG,
                account.jid.asBareJid().toString() + ": skipping republication of avatar because pep is broken"
            )
            return
        }
        val packet = this.iqGenerator.retrieveAvatarMetaData(null)
        this.sendIqPacket(account, packet, object : OnIqPacketReceived {

            fun parseAvatar(packet: IqPacket): Avatar? {
                val pubsub = packet.findChild("pubsub", "http://jabber.org/protocol/pubsub")
                if (pubsub != null) {
                    val items = pubsub.findChild("items")
                    if (items != null) {
                        return Avatar.parseMetadata(items)
                    }
                }
                return null
            }

            fun errorIsItemNotFound(packet: IqPacket): Boolean {
                val error = packet.findChild("error")
                return (packet.type == IqPacket.TYPE.ERROR
                        && error != null
                        && error.hasChild("item-not-found"))
            }

            override fun onIqPacketReceived(account: Account, packet: IqPacket) {
                if (packet.type == IqPacket.TYPE.RESULT || errorIsItemNotFound(packet)) {
                    val serverAvatar = parseAvatar(packet)
                    if (serverAvatar == null && account.avatar != null) {
                        val avatar = fileBackend.getStoredPepAvatar(account.avatar)
                        if (avatar != null) {
                            Log.d(
                                Config.LOGTAG,
                                account.jid.asBareJid().toString() + ": avatar on server was null. republishing"
                            )
                            publishAvatar(
                                account,
                                fileBackend.getStoredPepAvatar(account.avatar),
                                null
                            )
                        } else {
                            Log.e(
                                Config.LOGTAG,
                                account.jid.asBareJid().toString() + ": error rereading avatar"
                            )
                        }
                    }
                }
            }
        })
    }

    @JvmOverloads
    fun fetchAvatar(account: Account, avatar: Avatar, callback: UiCallback<Avatar>? = null) {
        val KEY = generateFetchKey(account, avatar)
        synchronized(this.mInProgressAvatarFetches) {
            if (mInProgressAvatarFetches.add(KEY)) {
                when (avatar.origin) {
                    Avatar.Origin.PEP -> {
                        this.mInProgressAvatarFetches.add(KEY)
                        fetchAvatarPep(account, avatar, callback)
                    }
                    Avatar.Origin.VCARD -> {
                        this.mInProgressAvatarFetches.add(KEY)
                        fetchAvatarVcard(account, avatar, callback)
                    }
                }
            } else if (avatar.origin == Avatar.Origin.PEP) {
                mOmittedPepAvatarFetches.add(KEY)
            } else {
                Log.d(
                    Config.LOGTAG,
                    account.jid.asBareJid().toString() + ": already fetching " + avatar.origin + " avatar for " + avatar.owner
                )
            }
        }
    }

    fun fetchAvatarPep(account: Account, avatar: Avatar?, callback: UiCallback<Avatar>?) {
        val packet = this.iqGenerator.retrievePepAvatar(avatar)
        sendIqPacket(account, packet, OnIqPacketReceived { a, result ->
            synchronized(mInProgressAvatarFetches) {
                mInProgressAvatarFetches.remove(generateFetchKey(a, avatar!!))
            }
            val ERROR =
                a.getJid().asBareJid().toString() + ": fetching avatar for " + avatar!!.owner + " failed "
            if (result.getType() == IqPacket.TYPE.RESULT) {
                avatar.image = iqParser.avatarData(result)
                if (avatar.image != null) {
                    if (fileBackend.save(avatar)) {
                        if (a.getJid().asBareJid() == avatar.owner) {
                            if (a.setAvatar(avatar.filename)) {
                                databaseBackend.updateAccount(a)
                            }
                            avatarService.clear(a)
                            updateConversationUi()
                            updateAccountUi()
                        } else {
                            val contact = a.getRoster().getContact(avatar.owner)
                            if (contact.setAvatar(avatar)) {
                                syncRoster(account)
                                avatarService.clear(contact)
                                updateConversationUi()
                                updateRosterUi()
                            }
                        }
                        callback?.success(avatar)
                        Log.d(
                            Config.LOGTAG, a.getJid().asBareJid().toString() + ": successfully fetched pep avatar for " + avatar.owner
                        )
                        return@OnIqPacketReceived
                    }
                } else {

                    Timber.d(ERROR + "(parsing error)")
                }
            } else {
                val error = result.findChild("error")
                if (error == null) {
                    Timber.d(ERROR + "(server error)")
                } else {
                    Timber.d(ERROR + error!!.toString())
                }
            }
            callback?.error(0, null)

        })
    }

    fun fetchAvatarVcard(account: Account, avatar: Avatar, callback: UiCallback<Avatar>?) {
        val packet = this.iqGenerator.retrieveVcardAvatar(avatar)
        this.sendIqPacket(account, packet, OnIqPacketReceived { account, packet ->
            val previouslyOmittedPepFetch: Boolean
            synchronized(mInProgressAvatarFetches) {
                val KEY = generateFetchKey(account, avatar)
                mInProgressAvatarFetches.remove(KEY)
                previouslyOmittedPepFetch = mOmittedPepAvatarFetches.remove(KEY)
            }
            if (packet.type == IqPacket.TYPE.RESULT) {
                val vCard = packet.findChild("vCard", "vcard-temp")
                val photo = vCard?.findChild("PHOTO")
                val image = photo?.findChildContent("BINVAL")
                if (image != null) {
                    avatar.image = image
                    if (fileBackend.save(avatar)) {
                        Log.d(
                            Config.LOGTAG, account.jid.asBareJid().toString()
                                    + ": successfully fetched vCard avatar for " + avatar.owner + " omittedPep=" + previouslyOmittedPepFetch
                        )
                        if (avatar.owner.isBareJid) {
                            if (account.jid.asBareJid() == avatar.owner && account.avatar == null) {
                                Log.d(
                                    Config.LOGTAG,
                                    account.jid.asBareJid().toString() + ": had no avatar. replacing with vcard"
                                )
                                account.avatar = avatar.filename
                                databaseBackend.updateAccount(account)
                                avatarService.clear(account)
                                updateAccountUi()
                            } else {
                                val contact = account.roster.getContact(avatar.owner)
                                if (contact.setAvatar(avatar, previouslyOmittedPepFetch)) {
                                    syncRoster(account)
                                    avatarService.clear(contact)
                                    updateRosterUi()
                                }
                            }
                            updateConversationUi()
                        } else {
                            val conversation = find(account, avatar.owner.asBareJid())
                            if (conversation != null && conversation.mode == Conversation.MODE_MULTI) {
                                val user = conversation.mucOptions.findUserByFullJid(avatar.owner)
                                if (user != null) {
                                    if (user.setAvatar(avatar)) {
                                        avatarService.clear(user)
                                        updateConversationUi()
                                        updateMucRosterUi()
                                    }
                                    if (user.realJid != null) {
                                        val contact = account.roster.getContact(user.realJid)
                                        if (contact.setAvatar(avatar)) {
                                            syncRoster(account)
                                            avatarService.clear(contact)
                                            updateRosterUi()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })
    }

    fun checkForAvatar(account: Account, callback: UiCallback<Avatar>) {
        val packet = this.iqGenerator.retrieveAvatarMetaData(null)
        this.sendIqPacket(account, packet, OnIqPacketReceived { account, packet ->
            if (packet.type == IqPacket.TYPE.RESULT) {
                val pubsub = packet.findChild("pubsub", "http://jabber.org/protocol/pubsub")
                if (pubsub != null) {
                    val items = pubsub.findChild("items")
                    if (items != null) {
                        val avatar = Avatar.parseMetadata(items)
                        if (avatar != null) {
                            avatar.owner = account.jid.asBareJid()
                            if (fileBackend.isAvatarCached(avatar)) {
                                if (account.setAvatar(avatar.filename)) {
                                    databaseBackend.updateAccount(account)
                                }
                                avatarService.clear(account)
                                callback.success(avatar)
                            } else {
                                fetchAvatarPep(account, avatar, callback)
                            }
                            return@OnIqPacketReceived
                        }
                    }
                }
            }
            callback.error(0, null)
        })
    }

    fun notifyAccountAvatarHasChanged(account: Account) {
        val connection = account.xmppConnection
        if (connection != null && connection.features.bookmarksConversion()) {
            Log.d(
                Config.LOGTAG,
                account.jid.asBareJid().toString() + ": avatar changed. resending presence to online group chats"
            )
            for (conversation in conversations) {
                if (conversation.account === account && conversation.mode == Conversational.MODE_MULTI) {
                    val mucOptions = conversation.mucOptions
                    if (mucOptions.online()) {
                        val packet = presenceGenerator.selfPresence(
                            account,
                            Presence.Status.ONLINE,
                            mucOptions.nonanonymous()
                        )
                        packet.to = mucOptions.self.fullJid
                        connection.sendPresencePacket(packet)
                    }
                }
            }
        }
    }

    fun deleteContactOnServer(contact: Contact) {
        contact.resetOption(Contact.Options.PREEMPTIVE_GRANT)
        contact.resetOption(Contact.Options.DIRTY_PUSH)
        contact.setOption(Contact.Options.DIRTY_DELETE)
        val account = contact.account
        if (account.status == Account.State.ONLINE) {
            val iq = IqPacket(IqPacket.TYPE.SET)
            val item = iq.query(Namespace.ROSTER).addChild("item")
            item.setAttribute("jid", contact.jid.toString())
            item.setAttribute("subscription", "remove")
            account.xmppConnection.sendIqPacket(iq, mDefaultIqHandler)
        }
    }

    fun updateConversation(conversation: Conversation) {
        mDatabaseWriterExecutor.execute { databaseBackend.updateConversation(conversation) }
    }

    fun reconnectAccount(account: Account, force: Boolean, interactive: Boolean) {
        synchronized(account) {
            var connection: XmppConnection? = account.xmppConnection
            if (connection == null) {
                connection = createConnection(account)
                account.xmppConnection = connection
            }
            val hasInternet = hasInternetConnection()
            if (account.isEnabled && hasInternet) {
                if (!force) {
                    disconnect(account, false)
                }
                val thread = Thread(connection)
                connection.setInteractive(interactive)
                connection.prepareNewConnection()
                connection.interrupt()
                thread.start()
                scheduleWakeUpCall(Config.CONNECT_DISCO_TIMEOUT, account.uuid.hashCode())
            } else {
                disconnect(account, force || account.trueStatus.isError || !hasInternet)
                account.roster.clearPresences()
                connection.resetEverything()
                val axolotlService = account.axolotlService
                axolotlService?.resetBrokenness()
                if (!hasInternet) {
                    account.status = Account.State.NO_INTERNET
                }
            }
        }
    }

    fun reconnectAccountInBackground(account: Account) {
        Thread { reconnectAccount(account, false, true) }.start()
    }

    fun invite(conversation: Conversation, contact: Jid) {
        Log.d(
            Config.LOGTAG,
            conversation.account.jid.asBareJid().toString() + ": inviting " + contact + " to " + conversation.jid.asBareJid()
        )
        val packet = messageGenerator.invite(conversation, contact)
        sendMessagePacket(conversation.account, packet)
    }

    fun directInvite(conversation: Conversation, jid: Jid) {
        val packet = messageGenerator.directInvite(conversation, jid)
        sendMessagePacket(conversation.account, packet)
    }

    fun resetSendingToWaiting(account: Account) {
        for (conversation in getConversations()) {
            if (conversation.account === account) {
                conversation.findUnsentTextMessages { message ->
                    markMessage(
                        message,
                        Message.STATUS_WAITING
                    )
                }
            }
        }
    }

    @JvmOverloads
    fun markMessage(
        account: Account,
        recipient: Jid,
        uuid: String?,
        status: Int,
        errorMessage: String? = null
    ): Message? {
        if (uuid == null) {
            return null
        }
        for (conversation in getConversations()) {
            if (conversation.jid.asBareJid() == recipient && conversation.account === account) {
                val message = conversation.findSentMessageWithUuidOrRemoteId(uuid)
                if (message != null) {
                    markMessage(message, status, errorMessage)
                }
                return message
            }
        }
        return null
    }

    fun markMessage(
        conversation: Conversation,
        uuid: String?,
        status: Int,
        serverMessageId: String
    ): Boolean {
        if (uuid == null) {
            return false
        } else {
            val message = conversation.findSentMessageWithUuid(uuid)
            if (message != null) {
                if (message.serverMsgId == null) {
                    message.serverMsgId = serverMessageId
                }
                markMessage(message, status)
                return true
            } else {
                return false
            }
        }
    }


    @JvmOverloads
    fun markMessage(message: Message, status: Int, errorMessage: String? = null) {
        val c = message.status
        if (status == Message.STATUS_SEND_FAILED && (c == Message.STATUS_SEND_RECEIVED || c == Message.STATUS_SEND_DISPLAYED)) {
            return
        }
        if (status == Message.STATUS_SEND_RECEIVED && c == Message.STATUS_SEND_DISPLAYED) {
            return
        }
        message.errorMessage = errorMessage
        message.status = status
        databaseBackend.updateMessage(message, false)
        updateConversationUi()
    }

    fun getLongPreference(name: String, @IntegerRes res: Int): Long {
        val defaultValue = resources.getInteger(res).toLong()
        try {
            return java.lang.Long.parseLong(preferences.getString(name, defaultValue.toString())!!)
        } catch (e: NumberFormatException) {
            return defaultValue
        }

    }

    fun getBooleanPreference(name: String, @BoolRes res: Int): Boolean {
        return preferences.getBoolean(name, resources.getBoolean(res))
    }

    fun confirmMessages(): Boolean {
        return getBooleanPreference("confirm_messages", R.bool.confirm_messages)
    }

    fun allowMessageCorrection(): Boolean {
        return getBooleanPreference("allow_message_correction", R.bool.allow_message_correction)
    }

    fun sendChatStates(): Boolean {
        return getBooleanPreference("chat_states", R.bool.chat_states)
    }

    fun synchronizeWithBookmarks(): Boolean {
        return getBooleanPreference("autojoin", R.bool.autojoin)
    }

    fun indicateReceived(): Boolean {
        return getBooleanPreference("indicate_received", R.bool.indicate_received)
    }

    fun useTorToConnect(): Boolean {
        return QuickConversationsService.isConversations() && getBooleanPreference(
            "use_tor",
            R.bool.use_tor
        )
    }

    fun showExtendedConnectionOptions(): Boolean {
        return QuickConversationsService.isConversations() && getBooleanPreference(
            "show_connection_options",
            R.bool.show_connection_options
        )
    }

    fun broadcastLastActivity(): Boolean {
        return getBooleanPreference(SettingsActivity.BROADCAST_LAST_ACTIVITY, R.bool.last_activity)
    }

    fun unreadCount(): Int {
        var count = 0
        for (conversation in getConversations()) {
            count += conversation.unreadCount()
        }
        return count
    }


    fun <T> threadSafeList(set: Set<T>): List<T> {
        synchronized(LISTENER_LOCK) {
            return if (set.size == 0) emptyList() else ArrayList(set)
        }
    }

    fun showErrorToastInUi(resId: Int) {
        for (listener in threadSafeList(this.mOnShowErrorToasts)) {
            listener.onShowErrorToast(resId)
        }
    }

    fun updateConversationUi() {
        for (listener in threadSafeList(this.mOnConversationUpdates)) {
            listener.onConversationUpdate()
        }
    }

    fun updateAccountUi() {
        for (listener in threadSafeList(this.mOnAccountUpdates)) {
            listener.onAccountUpdate()
        }
    }

    fun updateRosterUi() {
        for (listener in threadSafeList(this.mOnRosterUpdates)) {
            listener.onRosterUpdate()
        }
    }

    fun displayCaptchaRequest(account: Account, id: String, data: Data, captcha: Bitmap): Boolean {
        if (mOnCaptchaRequested.size > 0) {
            val metrics = applicationContext.resources.displayMetrics
            val scaled = Bitmap.createScaledBitmap(
                captcha, (captcha.width * metrics.scaledDensity).toInt(),
                (captcha.height * metrics.scaledDensity).toInt(), false
            )
            for (listener in threadSafeList(this.mOnCaptchaRequested)) {
                listener.onCaptchaRequested(account, id, data, scaled)
            }
            return true
        }
        return false
    }

    fun updateBlocklistUi(status: OnUpdateBlocklist.Status) {
        for (listener in threadSafeList(this.mOnUpdateBlocklist)) {
            listener.OnUpdateBlocklist(status)
        }
    }

    fun updateMucRosterUi() {
        for (listener in threadSafeList(this.mOnMucRosterUpdate)) {
            listener.onMucRosterUpdate()
        }
    }

    fun keyStatusUpdated(report: AxolotlService.FetchStatus?) {
        for (listener in threadSafeList(this.mOnKeyStatusUpdated)) {
            listener.onKeyStatusUpdated(report)
        }
    }

    fun findAccountByJid(accountJid: Jid): Account? {
        for (account in this.accounts!!) {
            if (account.jid.asBareJid() == accountJid.asBareJid()) {
                return account
            }
        }
        return null
    }

    fun findAccountByUuid(uuid: String): Account? {
        for (account in this.accounts!!) {
            if (account.uuid == uuid) {
                return account
            }
        }
        return null
    }

    fun findConversationByUuid(uuid: String): Conversation? {
        for (conversation in getConversations()) {
            if (conversation.uuid == uuid) {
                return conversation
            }
        }
        return null
    }

    fun findUniqueConversationByJid(xmppUri: XmppUri): Conversation? {
        val findings = ArrayList<Conversation>()
        for (c in getConversations()) {
            if (c.account.isEnabled && c.jid.asBareJid() == xmppUri.jid && c.mode == Conversational.MODE_MULTI == xmppUri.isAction(
                    XmppUri.ACTION_JOIN
                )
            ) {
                findings.add(c)
            }
        }
        return if (findings.size == 1) findings[0] else null
    }

    fun markRead(conversation: Conversation, dismiss: Boolean): Boolean {
        return markRead(conversation, null, dismiss).size > 0
    }

    fun markRead(conversation: Conversation) {
        markRead(conversation, null, true)
    }

    fun markRead(conversation: Conversation, upToUuid: String?, dismiss: Boolean): List<Message> {
        if (dismiss) {
            notificationService.clear(conversation)
        }
        val readMessages = conversation.markRead(upToUuid)
        if (readMessages.size > 0) {
            val runnable = {
                for (message in readMessages) {
                    databaseBackend.updateMessage(message, false)
                }
            }
            mDatabaseWriterExecutor.execute(runnable)
            updateUnreadCountBadge()
            return readMessages
        } else {
            return readMessages
        }
    }

    @Synchronized
    fun updateUnreadCountBadge() {
        val count = unreadCount()
        if (unreadCount != count) {
            Timber.d("update unread count to $count")
            if (count > 0) {
                ShortcutBadger.applyCount(applicationContext, count)
            } else {
                ShortcutBadger.removeCount(applicationContext)
            }
            unreadCount = count
        }
    }

    fun sendReadMarker(conversation: Conversation, upToUuid: String?) {
        val isPrivateAndNonAnonymousMuc =
            conversation.mode == Conversation.MODE_MULTI && conversation.isPrivateAndNonAnonymous
        val readMessages = this.markRead(conversation, upToUuid, true)
        if (readMessages.size > 0) {
            updateConversationUi()
        }
        val markable =
            Conversation.getLatestMarkableMessage(readMessages, isPrivateAndNonAnonymousMuc)
        if (confirmMessages()
            && markable != null
            && (markable.trusted() || isPrivateAndNonAnonymousMuc)
            && markable.remoteMsgId != null
        ) {
            Log.d(
                Config.LOGTAG,
                conversation.account.jid.asBareJid().toString() + ": sending read marker to " + markable.counterpart.toString()
            )
            val account = conversation.account
            val to = markable.counterpart
            val groupChat = conversation.mode == Conversation.MODE_MULTI
            val packet = messageGenerator.confirm(
                account,
                to,
                markable.remoteMsgId,
                markable.counterpart,
                groupChat
            )
            this.sendMessagePacket(conversation.account, packet)
        }
    }

    fun updateMemorizingTrustmanager() {
        val tm: MemorizingTrustManager
        val dontTrustSystemCAs =
            getBooleanPreference("dont_trust_system_cas", R.bool.dont_trust_system_cas)
        if (dontTrustSystemCAs) {
            tm = MemorizingTrustManager(applicationContext, null)
        } else {
            tm = MemorizingTrustManager(applicationContext)
        }
        memorizingTrustManager = tm
    }

    fun sendMessagePacket(account: Account, packet: MessagePacket) {
        val connection = account.xmppConnection
        connection?.sendMessagePacket(packet)
    }

    fun sendPresencePacket(account: Account, packet: PresencePacket) {
        val connection = account.xmppConnection
        connection?.sendPresencePacket(packet)
    }

    fun sendCreateAccountWithCaptchaPacket(account: Account, id: String, data: Data) {
        val connection = account.xmppConnection
        if (connection != null) {
            val request = iqGenerator.generateCreateAccountWithCaptcha(account, id, data)
            connection.sendUnmodifiedIqPacket(
                request,
                connection.registrationResponseListener,
                true
            )
        }
    }

    fun sendIqPacket(account: Account, packet: IqPacket, callback: OnIqPacketReceived?) {
        val connection = account.xmppConnection
        if (connection != null) {
            connection.sendIqPacket(packet, callback)
        } else callback?.onIqPacketReceived(account, IqPacket(IqPacket.TYPE.TIMEOUT))
    }

    fun sendPresence(account: Account) {
        sendPresence(account, checkListeners() && broadcastLastActivity())
    }

    fun sendPresence(account: Account, includeIdleTimestamp: Boolean) {
        val status: Presence.Status
        if (manuallyChangePresence()) {
            status = account.presenceStatus
        } else {
            status = targetPresence
        }
        val packet = presenceGenerator.selfPresence(account, status)
        val message = account.presenceStatusMessage
        if (message != null && !message.isEmpty()) {
            packet.addChild(Element("status").setContent(message))
        }
        if (mLastActivity > 0 && includeIdleTimestamp) {
            val since =
                Math.min(mLastActivity, System.currentTimeMillis()) //don't send future dates
            packet.addChild("idle", Namespace.IDLE)
                .setAttribute("since", AbstractGenerator.getTimestamp(since))
        }
        sendPresencePacket(account, packet)
    }

    fun deactivateGracePeriod() {
        for (account in accounts!!) {
            account.deactivateGracePeriod()
        }
    }

    fun refreshAllPresences() {
        val includeIdleTimestamp = checkListeners() && broadcastLastActivity()
        for (account in accounts!!) {
            if (account.isEnabled) {
                sendPresence(account, includeIdleTimestamp)
            }
        }
    }

    fun refreshAllFcmTokens() {
        for (account in accounts!!) {
            if (account.isOnlineAndConnected && pushManagementService.available(account)) {
                pushManagementService.registerPushTokenOnServer(account)
            }
        }
    }

    fun sendOfflinePresence(account: Account) {
        Timber.d(account.jid.asBareJid().toString() + ": sending offline presence")
        sendPresencePacket(account, presenceGenerator.sendOfflinePresence(account))
    }

    fun findContacts(jid: Jid, accountJid: String?): List<Contact> {
        val contacts = ArrayList<Contact>()
        for (account in accounts!!) {
            if ((account.isEnabled || accountJid != null) && (accountJid == null || accountJid == account.jid.asBareJid().toString())) {
                val contact = account.roster.getContactFromContactList(jid)
                if (contact != null) {
                    contacts.add(contact)
                }
            }
        }
        return contacts
    }

    fun findFirstMuc(jid: Jid): Conversation? {
        for (conversation in getConversations()) {
            if (conversation.account.isEnabled && conversation.jid.asBareJid() == jid.asBareJid() && conversation.mode == Conversation.MODE_MULTI) {
                return conversation
            }
        }
        return null
    }

    fun resendFailedMessages(message: Message) {
        val messages = ArrayList<Message>()
        var current: Message? = message
        while (current!!.status == Message.STATUS_SEND_FAILED) {
            messages.add(current)
            if (current.mergeable(current.next())) {
                current = current.next()
            } else {
                break
            }
        }
        for (msg in messages) {
            msg.setTime(System.currentTimeMillis())
            markMessage(msg, Message.STATUS_WAITING)
            this.resendMessage(msg, false)
        }
        if (message.conversation is Conversation) {
            (message.conversation as Conversation).sort()
        }
        updateConversationUi()
    }

    fun clearConversationHistory(conversation: Conversation) {
        val clearDate: Long
        val reference: String?
        if (conversation.countMessages() > 0) {
            val latestMessage = conversation.latestMessage
            clearDate = latestMessage.timeSent + 1000
            reference = latestMessage.serverMsgId
        } else {
            clearDate = System.currentTimeMillis()
            reference = null
        }
        conversation.clearMessages()
        conversation.setHasMessagesLeftOnServer(false) //avoid messages getting loaded through mam
        conversation.setLastClearHistory(clearDate, reference)
        val runnable = {
            databaseBackend.deleteMessagesInConversation(conversation)
            databaseBackend.updateConversation(conversation)
        }
        mDatabaseWriterExecutor.execute(runnable)
    }

    fun sendBlockRequest(blockable: Blockable?, reportSpam: Boolean): Boolean {
        if (blockable != null && blockable.blockedJid != null) {
            val jid = blockable.blockedJid
            this.sendIqPacket(
                blockable.account,
                iqGenerator.generateSetBlockRequest(jid, reportSpam),
                OnIqPacketReceived { account, packet ->
                    if (packet.type == IqPacket.TYPE.RESULT) {
                        account.blocklist.add(jid)
                        updateBlocklistUi(OnUpdateBlocklist.Status.BLOCKED)
                    }
                })
            if (removeBlockedConversations(blockable.account, jid)) {
                updateConversationUi()
                return true
            } else {
                return false
            }
        } else {
            return false
        }
    }

    fun removeBlockedConversations(account: Account, blockedJid: Jid): Boolean {
        var removed = false
        synchronized(this.conversations) {
            val domainJid = blockedJid.local == null
            for (conversation in this.conversations) {
                val jidMatches =
                    domainJid && blockedJid.domain == conversation.jid.domain || blockedJid == conversation.jid.asBareJid()
                if (conversation.account === account
                    && conversation.mode == Conversation.MODE_SINGLE
                    && jidMatches
                ) {
                    this.conversations.remove(conversation)
                    markRead(conversation)
                    conversation.status = Conversation.STATUS_ARCHIVED
                    Log.d(
                        Config.LOGTAG,
                        account.jid.asBareJid().toString() + ": archiving conversation " + conversation.jid.asBareJid().toString() + " because jid was blocked"
                    )
                    updateConversation(conversation)
                    removed = true
                }
            }
        }
        return removed
    }

    fun sendUnblockRequest(blockable: Blockable?) {
        if (blockable != null && blockable.jid != null) {
            val jid = blockable.blockedJid
            this.sendIqPacket(
                blockable.account,
                iqGenerator.generateSetUnblockRequest(jid),
                OnIqPacketReceived { account, packet ->
                    if (packet.type == IqPacket.TYPE.RESULT) {
                        account.blocklist.remove(jid)
                        updateBlocklistUi(OnUpdateBlocklist.Status.UNBLOCKED)
                    }
                })
        }
    }

    fun publishDisplayName(account: Account) {
        val displayName = account.displayName
        val request: IqPacket
        if (TextUtils.isEmpty(displayName)) {
            request = iqGenerator.deleteNode(Namespace.NICK)
        } else {
            request = iqGenerator.publishNick(displayName)
        }
        avatarService.clear(account)
        sendIqPacket(account, request, OnIqPacketReceived { account1, packet ->
            if (packet.getType() == IqPacket.TYPE.ERROR) {
                Log.d(
                    Config.LOGTAG,
                    account1.getJid().asBareJid().toString() + ": unable to modify nick name " + packet.toString()
                )
            }
        })
    }

    fun getCachedServiceDiscoveryResult(key: Pair<String, String>): ServiceDiscoveryResult? {
        var result: ServiceDiscoveryResult? = discoCache.get(key)
        if (result != null) {
            return result
        } else {
            result = databaseBackend.findDiscoveryResult(key.first, key.second)
            if (result != null) {
                discoCache.put(key, result)
            }
            return result
        }
    }

    fun fetchCaps(account: Account, jid: Jid, presence: Presence) {
        val key = Pair(presence.hash, presence.ver)
        val disco = getCachedServiceDiscoveryResult(key)
        if (disco != null) {
            presence.serviceDiscoveryResult = disco
        } else {
            if (!account.inProgressDiscoFetches.contains(key)) {
                account.inProgressDiscoFetches.add(key)
                val request = IqPacket(IqPacket.TYPE.GET)
                request.to = jid
                val node = presence.node
                val ver = presence.ver
                val query = request.query("http://jabber.org/protocol/disco#info")
                if (node != null && ver != null) {
                    query.setAttribute("node", "$node#$ver")
                }
                Log.d(
                    Config.LOGTAG,
                    account.jid.asBareJid().toString() + ": making disco request for " + key.second + " to " + jid
                )
                sendIqPacket(account, request, OnIqPacketReceived { a, response ->
                    if (response.getType() == IqPacket.TYPE.RESULT) {
                        val discoveryResult = ServiceDiscoveryResult(response)
                        if (presence.ver == discoveryResult.getVer()) {
                            databaseBackend.insertDiscoveryResult(discoveryResult)
                            injectServiceDiscoveryResult(
                                a.getRoster(),
                                presence.hash,
                                presence.ver,
                                discoveryResult
                            )
                        } else {
                            Log.d(
                                Config.LOGTAG,
                                a.getJid().asBareJid().toString() + ": mismatch in caps for contact " + jid + " " + presence.ver + " vs " + discoveryResult.getVer()
                            )
                        }
                    }
                    a.inProgressDiscoFetches.remove(key)
                })
            }
        }
    }

    fun injectServiceDiscoveryResult(
        roster: Roster,
        hash: String,
        ver: String,
        disco: ServiceDiscoveryResult
    ) {
        for (contact in roster.contacts) {
            for (presence in contact.presences.presences.values) {
                if (hash == presence.hash && ver == presence.ver) {
                    presence.serviceDiscoveryResult = disco
                }
            }
        }
    }

    fun fetchMamPreferences(account: Account, callback: OnMamPreferencesFetched) {
        val version = MessageArchiveService.Version.get(account)
        val request = IqPacket(IqPacket.TYPE.GET)
        request.addChild("prefs", version.namespace)
        sendIqPacket(account, request, OnIqPacketReceived { account1, packet ->
            val prefs = packet.findChild("prefs", version.namespace)
            if (packet.getType() == IqPacket.TYPE.RESULT && prefs != null) {
                callback.onPreferencesFetched(prefs)
            } else {
                callback.onPreferencesFetchFailed()
            }
        })
    }

    fun changeStatus(account: Account, template: PresenceTemplate, signature: String) {
        if (!template.statusMessage.isEmpty()) {
            databaseBackend.insertPresenceTemplate(template)
        }
        account.pgpSignature = signature
        account.presenceStatus = template.status
        account.presenceStatusMessage = template.statusMessage
        databaseBackend.updateAccount(account)
        sendPresence(account)
    }

    fun getPresenceTemplates(account: Account): List<PresenceTemplate> {
        val templates = databaseBackend.presenceTemplates
        for (template in account.selfContact.presences.asTemplates()) {
            if (!templates.contains(template)) {
                templates.add(0, template)
            }
        }
        return templates
    }

    fun saveConversationAsBookmark(conversation: Conversation, name: String?) {
        val account = conversation.account
        val bookmark = Bookmark(account, conversation.jid.asBareJid())
        if (!conversation.jid.isBareJid) {
            bookmark.nick = conversation.jid.resource
        }
        if (!TextUtils.isEmpty(name)) {
            bookmark.bookmarkName = name
        }
        bookmark.setAutojoin(
            preferences.getBoolean(
                "autojoin",
                resources.getBoolean(R.bool.autojoin)
            )
        )
        account.bookmarks.add(bookmark)
        pushBookmarks(account)
        bookmark.conversation = conversation
    }

    fun verifyFingerprints(contact: Contact, fingerprints: List<XmppUri.Fingerprint>): Boolean {
        var performedVerification = false
        val axolotlService = contact.account.axolotlService
        for (fp in fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OMEMO) {
                val fingerprint = "05" + fp.fingerprint.replace("\\s".toRegex(), "")
                val fingerprintStatus = axolotlService.getFingerprintTrust(fingerprint)
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified) {
                        performedVerification = true
                        axolotlService.setFingerprintTrust(
                            fingerprint,
                            fingerprintStatus.toVerified()
                        )
                    }
                } else {
                    axolotlService.preVerifyFingerprint(contact, fingerprint)
                }
            }
        }
        return performedVerification
    }

    fun verifyFingerprints(account: Account, fingerprints: List<XmppUri.Fingerprint>): Boolean {
        val axolotlService = account.axolotlService
        var verifiedSomething = false
        for (fp in fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OMEMO) {
                val fingerprint = "05" + fp.fingerprint.replace("\\s".toRegex(), "")
                Timber.d("trying to verify own fp=$fingerprint")
                val fingerprintStatus = axolotlService.getFingerprintTrust(fingerprint)
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified) {
                        axolotlService.setFingerprintTrust(
                            fingerprint,
                            fingerprintStatus.toVerified()
                        )
                        verifiedSomething = true
                    }
                } else {
                    axolotlService.preVerifyFingerprint(account, fingerprint)
                    verifiedSomething = true
                }
            }
        }
        return verifiedSomething
    }

    fun blindTrustBeforeVerification(): Boolean {
        return getBooleanPreference(SettingsActivity.BLIND_TRUST_BEFORE_VERIFICATION, R.bool.btbv)
    }

    fun pushMamPreferences(account: Account, prefs: Element) {
        val set = IqPacket(IqPacket.TYPE.SET)
        set.addChild(prefs)
        sendIqPacket(account, set, null)
    }

    interface OnMamPreferencesFetched {
        fun onPreferencesFetched(prefs: Element)

        fun onPreferencesFetchFailed()
    }

    interface OnAccountCreated {
        fun onAccountCreated(account: Account)

        fun informUser(r: Int)
    }

    interface OnMoreMessagesLoaded {
        fun onMoreMessagesLoaded(count: Int, conversation: Conversation)

        fun informUser(r: Int)
    }

    interface OnAccountPasswordChanged {
        fun onPasswordChangeSucceeded()

        fun onPasswordChangeFailed()
    }

    interface OnRoomDestroy {
        fun onRoomDestroySucceeded()

        fun onRoomDestroyFailed()
    }

    interface OnAffiliationChanged {
        fun onAffiliationChangedSuccessful(jid: Jid)

        fun onAffiliationChangeFailed(jid: Jid, resId: Int)
    }

    interface OnConversationUpdate {
        fun onConversationUpdate()
    }

    interface OnAccountUpdate {
        fun onAccountUpdate()
    }

    interface OnCaptchaRequested {
        fun onCaptchaRequested(account: Account, id: String, data: Data, captcha: Bitmap)
    }

    interface OnRosterUpdate {
        fun onRosterUpdate()
    }

    interface OnMucRosterUpdate {
        fun onMucRosterUpdate()
    }

    interface OnConferenceConfigurationFetched {
        fun onConferenceConfigurationFetched(conversation: Conversation)

        fun onFetchFailed(conversation: Conversation, error: Element?)
    }

    interface OnConferenceJoined : (Conversation) -> Unit


    interface OnChannelSearchResultsFound {
        fun onChannelSearchResultsFound(results: List<MuclumbusService.Room>)
    }

    interface OnConfigurationPushed {
        fun onPushSucceeded()

        fun onPushFailed()
    }

    interface OnShowErrorToast {
        fun onShowErrorToast(resId: Int)
    }

    inner class XmppConnectionBinder : Binder() {
        val service: XmppConnectionService
            get() = this@XmppConnectionService
    }

    inner class InternalEventReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            onStartCommand(intent, 0, 0)
        }
    }

    companion object {

        @JvmField
        val ACTION_REPLY_TO_CONVERSATION = "reply_to_conversations"
        @JvmField
        val ACTION_MARK_AS_READ = "mark_as_read"
        @JvmField
        val ACTION_SNOOZE = "snooze"
        @JvmField
        val ACTION_CLEAR_NOTIFICATION = "clear_notification"
        @JvmField
        val ACTION_DISMISS_ERROR_NOTIFICATIONS = "dismiss_error"
        @JvmField
        val ACTION_TRY_AGAIN = "try_again"
        @JvmField
        val ACTION_IDLE_PING = "idle_ping"
        @JvmField
        val ACTION_FCM_TOKEN_REFRESH = "fcm_token_refresh"
        @JvmField
        val ACTION_FCM_MESSAGE_RECEIVED = "fcm_message_received"
        val ACTION_POST_CONNECTIVITY_CHANGE =
            "eu.siacs.conversations.POST_CONNECTIVITY_CHANGE"

        val SETTING_LAST_ACTIVITY_TS = "last_activity_timestamp"

        init {
            URL.setURLStreamHandlerFactory(CustomURLStreamHandlerFactory())
        }

        fun generateFetchKey(account: Account, avatar: Avatar): String {
            return account.jid.asBareJid().toString() + "_" + avatar.owner + "_" + avatar.sha1sum
        }
    }
}
