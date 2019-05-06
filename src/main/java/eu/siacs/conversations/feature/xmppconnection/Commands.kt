package eu.siacs.conversations.feature.xmppconnection


import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
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
import eu.siacs.conversations.feature.di.ServiceScope
import eu.siacs.conversations.generator.AbstractGenerator
import eu.siacs.conversations.generator.IqGenerator
import eu.siacs.conversations.generator.MessageGenerator
import eu.siacs.conversations.generator.PresenceGenerator
import eu.siacs.conversations.http.HttpConnectionManager
import eu.siacs.conversations.http.services.MuclumbusService
import eu.siacs.conversations.parser.AbstractParser
import eu.siacs.conversations.parser.IqParser
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.services.*
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
import eu.siacs.conversations.xmpp.mam.MamReference
import eu.siacs.conversations.xmpp.pep.Avatar
import eu.siacs.conversations.xmpp.pep.PublishOptions
import eu.siacs.conversations.xmpp.stanzas.IqPacket
import eu.siacs.conversations.xmpp.stanzas.MessagePacket
import eu.siacs.conversations.xmpp.stanzas.PresencePacket
import io.aakit.scope.ActivityScope
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
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.inject.Inject


@ServiceScope
class SendReadMarker @Inject constructor(
    private val service: XmppConnectionService,
    private val updateConversationUi: UpdateConversationUi,
    private val confirmMessages: ConfirmMessages,
    private val messageGenerator: MessageGenerator
) {
    operator fun invoke(conversation: Conversation, upToUuid: String?) {
        val isPrivateAndNonAnonymousMuc =
            conversation.mode == Conversation.MODE_MULTI && conversation.isPrivateAndNonAnonymous
        val readMessages = service.markRead(conversation, upToUuid, true)
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
            Timber.d(conversation.account.jid.asBareJid().toString() + ": sending read marker to " + markable.counterpart.toString())
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
            service.sendMessagePacket(conversation.account, packet)
        }
    }
}

@ServiceScope
class UpdateMemorizingTrustmanager @Inject constructor(
    private val service: XmppConnectionService,
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke() {
        val tm: MemorizingTrustManager
        val dontTrustSystemCAs =
            getBooleanPreference("dont_trust_system_cas", R.bool.dont_trust_system_cas)
        if (dontTrustSystemCAs) {
            tm = MemorizingTrustManager(service.applicationContext, null)
        } else {
            tm = MemorizingTrustManager(service.applicationContext)
        }
        service.memorizingTrustManager = tm
    }
}

@ServiceScope
class SendMessagePacket @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(account: Account, packet: MessagePacket) {
        val connection = account.xmppConnection
        connection?.sendMessagePacket(packet)
    }
}

@ServiceScope
class SendPresencePacket @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(account: Account, packet: PresencePacket) {
        val connection = account.xmppConnection
        connection?.sendPresencePacket(packet)
    }
}

@ServiceScope
class SendCreateAccountWithCaptchaPacket @Inject constructor(
    private val service: XmppConnectionService,
    private val iqGenerator: IqGenerator
) {
    operator fun invoke(account: Account, id: String, data: Data) {
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
}

@ServiceScope
class SendIqPacket @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(account: Account, packet: IqPacket, callback: OnIqPacketReceived?) {
        val connection = account.xmppConnection
        if (connection != null) {
            connection.sendIqPacket(packet, callback)
        } else callback?.onIqPacketReceived(account, IqPacket(IqPacket.TYPE.TIMEOUT))
    }
}

@ServiceScope
class SendPresence @Inject constructor(
    private val service: XmppConnectionService,
    private val sendPresence: SendPresence,
    private val checkListeners: CheckListeners,
    private val broadcastLastActivity: BroadcastLastActivity,
    private val manuallyChangePresence: ManuallyChangePresence,
    private val presenceGenerator: PresenceGenerator,
    private val sendPresencePacket: SendPresencePacket
) {

    operator fun invoke(account: Account) {
        sendPresence(account, checkListeners() && broadcastLastActivity())
    }

    operator fun invoke(account: Account, includeIdleTimestamp: Boolean) {
        val status: Presence.Status
        if (manuallyChangePresence()) {
            status = account.presenceStatus
        } else {
            status = service.targetPresence
        }
        val packet = presenceGenerator.selfPresence(account, status)
        val message = account.presenceStatusMessage
        if (message != null && message.isNotEmpty()) {
            packet.addChild(Element("status").setContent(message))
        }
        if (service.mLastActivity > 0 && includeIdleTimestamp) {
            val since =
                Math.min(service.mLastActivity, System.currentTimeMillis()) //don't send future dates
            packet.addChild("idle", Namespace.IDLE)
                .setAttribute("since", AbstractGenerator.getTimestamp(since))
        }
        sendPresencePacket(account, packet)
    }
}

@ServiceScope
class DeactivateGracePeriod @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke() {
        for (account in service.accounts!!) {
            account.deactivateGracePeriod()
        }
    }
}

@ServiceScope
class RefreshAllPresences @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val broadcastLastActivity: BroadcastLastActivity,
    private val sendPresence: SendPresence
) {
    operator fun invoke() {
        val includeIdleTimestamp = checkListeners() && broadcastLastActivity()
        for (account in service.accounts!!) {
            if (account.isEnabled) {
                sendPresence(account, includeIdleTimestamp)
            }
        }
    }
}

@ServiceScope
class RefreshAllFcmTokens @Inject constructor(
    private val service: XmppConnectionService,
    private val pushManagementService: PushManagementService
) {
    operator fun invoke() {
        for (account in service.accounts!!) {
            if (account.isOnlineAndConnected && pushManagementService.available(account)) {
                pushManagementService.registerPushTokenOnServer(account)
            }
        }
    }
}

@ServiceScope
class SendOfflinePresence @Inject constructor(
    private val sendPresencePacket: SendPresencePacket,
    private val presenceGenerator: PresenceGenerator
) {
    operator fun invoke(account: Account) {
        Timber.d("${account.jid.asBareJid()}: sending offline presence")
        sendPresencePacket(account, presenceGenerator.sendOfflinePresence(account))
    }
}

@ServiceScope
class FindContacts @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(jid: Jid, accountJid: String?): List<Contact> {
        val contacts = ArrayList<Contact>()
        for (account in service.accounts!!) {
            if ((account.isEnabled || accountJid != null) && (accountJid == null || accountJid == account.jid.asBareJid().toString())) {
                val contact = account.roster.getContactFromContactList(jid)
                if (contact != null) {
                    contacts.add(contact)
                }
            }
        }
        return contacts
    }
}

@ServiceScope
class FindFirstMuc @Inject constructor(
    private val service: XmppConnectionService,
    private val getConversations: GetConversations
) {
    operator fun invoke(jid: Jid): Conversation? {
        for (conversation in getConversations()) {
            if (conversation.account.isEnabled && conversation.jid.asBareJid() == jid.asBareJid() && conversation.mode == Conversation.MODE_MULTI) {
                return conversation
            }
        }
        return null
    }
}

@ServiceScope
class ResendFailedMessages @Inject constructor(
    private val service: XmppConnectionService,
    private val markMessage: MarkMessage,
    private val updateConversationUi: UpdateConversationUi
) {
    operator fun invoke(message: Message) {
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
            service.resendMessage(msg, false)
        }
        if (message.conversation is Conversation) {
            (message.conversation as Conversation).sort()
        }
        updateConversationUi()
    }
}

@ServiceScope
class ClearConversationHistory @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend
) {
    operator fun invoke(conversation: Conversation) {
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
        service.mDatabaseWriterExecutor.execute(runnable)
    }
}

@ServiceScope
class SendBlockRequest @Inject constructor(
    private val service: XmppConnectionService,
    private val iqGenerator: IqGenerator,
    private val updateBlocklistUi: UpdateBlocklistUi,
    private val removeBlockedConversations: RemoveBlockedConversations,
    private val updateConversationUi: UpdateConversationUi
) {
    operator fun invoke(blockable: Blockable?, reportSpam: Boolean): Boolean {
        if (blockable != null && blockable.blockedJid != null) {
            val jid = blockable.blockedJid
            service.sendIqPacket(
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
}

@ServiceScope
class RemoveBlockedConversations @Inject constructor(
    private val service: XmppConnectionService,
    private val markRead: MarkRead,
    private val updateConversation: UpdateConversation
) {
    operator fun invoke(account: Account, blockedJid: Jid): Boolean {
        var removed = false
        synchronized(service.conversations) {
            val domainJid = blockedJid.local == null
            for (conversation in service.conversations) {
                val jidMatches =
                    domainJid && blockedJid.domain == conversation.jid.domain || blockedJid == conversation.jid.asBareJid()
                if (conversation.account === account
                    && conversation.mode == Conversation.MODE_SINGLE
                    && jidMatches
                ) {
                    service.conversations.remove(conversation)
                    markRead(conversation)
                    conversation.status = Conversation.STATUS_ARCHIVED
                    Timber.d("${account.jid.asBareJid()}: archiving conversation ${conversation.jid.asBareJid()} because jid was blocked")
                    updateConversation(conversation)
                    removed = true
                }
            }
        }
        return removed
    }
}

@ServiceScope
class SendUnblockRequest @Inject constructor(
    private val service: XmppConnectionService,
    private val iqGenerator: IqGenerator,
    private val updateBlocklistUi: UpdateBlocklistUi
) {
    operator fun invoke(blockable: Blockable?) {
        if (blockable != null && blockable.jid != null) {
            val jid = blockable.blockedJid
            service.sendIqPacket(
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
}

@ServiceScope
class PublishDisplayName @Inject constructor(
    private val service: XmppConnectionService,
    private val iqGenerator: IqGenerator,
    private val avatarService: AvatarService,
    private val sendIqPacket: SendIqPacket
) {
    operator fun invoke(account: Account) {
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
                Timber.d("${account1.getJid().asBareJid()}: unable to modify nick name $packet")
            }
        })
    }
}

@ServiceScope
class GetCachedServiceDiscoveryResult @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend
) {
    operator fun invoke(key: Pair<String, String>): ServiceDiscoveryResult? {
        var result: ServiceDiscoveryResult? = service.discoCache.get(key)
        if (result != null) {
            return result
        } else {
            result = databaseBackend.findDiscoveryResult(key.first, key.second)
            if (result != null) {
                service.discoCache.put(key, result)
            }
            return result
        }
    }
}

@ServiceScope
class FetchCaps @Inject constructor(
    private val service: XmppConnectionService,
    private val getCachedServiceDiscoveryResult: GetCachedServiceDiscoveryResult,
    private val sendIqPacket: SendIqPacket,
    private val databaseBackend: DatabaseBackend,
    private val injectServiceDiscoveryResult: InjectServiceDiscoveryResult
) {
    operator fun invoke(account: Account, jid: Jid, presence: Presence) {
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
                Timber.d(account.jid.asBareJid().toString() + ": making disco request for " + key.second + " to " + jid)
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
                            Timber.d(a.getJid().asBareJid().toString() + ": mismatch in caps for contact " + jid + " " + presence.ver + " vs " + discoveryResult.getVer())
                        }
                    }
                    a.inProgressDiscoFetches.remove(key)
                })
            }
        }
    }
}

@ServiceScope
class InjectServiceDiscoveryResult @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(
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
}

@ServiceScope
class FetchMamPreferences @Inject constructor(
    private val sendIqPacket: SendIqPacket
) {
    operator fun invoke(account: Account, callback: XmppConnectionService.OnMamPreferencesFetched) {
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
}

@ServiceScope
class ChangeStatus @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val sendPresence: SendPresence
) {
    operator fun invoke(account: Account, template: PresenceTemplate, signature: String) {
        if (!template.statusMessage.isEmpty()) {
            databaseBackend.insertPresenceTemplate(template)
        }
        account.pgpSignature = signature
        account.presenceStatus = template.status
        account.presenceStatusMessage = template.statusMessage
        databaseBackend.updateAccount(account)
        sendPresence(account)
    }
}

@ServiceScope
class GetPresenceTemplates @Inject constructor(
    private val databaseBackend: DatabaseBackend
) {
    operator fun invoke(account: Account): List<PresenceTemplate> {
        val templates = databaseBackend.presenceTemplates
        for (template in account.selfContact.presences.asTemplates()) {
            if (!templates.contains(template)) {
                templates.add(0, template)
            }
        }
        return templates
    }
}

@ServiceScope
class SaveConversationAsBookmark @Inject constructor(
    private val preferences: SharedPreferences,
    private val resources: Resources,
    private val pushBookmarks: PushBookmarks
) {
    operator fun invoke(conversation: Conversation, name: String?) {
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
}

@ServiceScope
class VerifyFingerprints @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(contact: Contact, fingerprints: List<XmppUri.Fingerprint>): Boolean {
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
    operator fun invoke(account: Account, fingerprints: List<XmppUri.Fingerprint>): Boolean {
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
}


@ServiceScope
class BlindTrustBeforeVerification @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference(
            SettingsActivity.BLIND_TRUST_BEFORE_VERIFICATION,
            R.bool.btbv
        )
    }
}

@ServiceScope
class PushMamPreferences @Inject constructor(
    private val sendIqPacket: SendIqPacket
) {
    operator fun invoke(account: Account, prefs: Element) {
        val set = IqPacket(IqPacket.TYPE.SET)
        set.addChild(prefs)
        sendIqPacket(account, set, null)
    }
}


@ActivityScope
class PgpEngine @Inject constructor(
    private val service: XmppConnectionService
) {

    operator fun invoke(): PgpEngine? = service.run {
        if (!Config.supportOpenPgp()) {
            return null
        } else if (pgpServiceConnection != null && pgpServiceConnection!!.isBound) {
            if (mPgpEngine == null) {
                mPgpEngine = PgpEngine(
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

}

@ActivityScope
class OpenPgpApi @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): OpenPgpApi? = service.run {
        if (!Config.supportOpenPgp()) {
            null
        } else if (pgpServiceConnection != null && pgpServiceConnection!!.isBound) {
            OpenPgpApi(this, pgpServiceConnection!!.service)
        } else {
            null
        }
    }

}

@ActivityScope
class IsDataSaverDisabled @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connectivityManager =
                service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return !connectivityManager.isActiveNetworkMetered || connectivityManager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
        } else {
            return true
        }
    }

}

@ActivityScope
class CompressPicturesPreference @Inject constructor(
    private val preferences: SharedPreferences,
    private val resources: Resources
) {
    operator fun invoke(): String? = preferences.getString(
        "picture_compression",
        resources.getString(R.string.picture_compression)
    )

}

@ActivityScope
class TargetPresence @Inject constructor(
    private val service: XmppConnectionService,
    private val dndOnSilentMode: DndOnSilentMode,
    private val awayWhenScreenOff: AwayWhenScreenOff
) {
    operator fun invoke(): Presence.Status = if (dndOnSilentMode() && service.isPhoneSilenced) {
        Presence.Status.DND
    } else if (awayWhenScreenOff() && !service.isInteractive) {
        Presence.Status.AWAY
    } else {
        Presence.Status.ONLINE
    }

}

@ActivityScope
class IsInteractive @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): Boolean {
        try {
            val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager

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

}

@ActivityScope
class IsPhoneSilenced @Inject constructor(
    private val service: XmppConnectionService,
    private val treatVibrateAsSilent: TreatVibrateAsSilent
) {
    operator fun invoke(): Boolean {
        val notificationDnd: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val notificationManager = service.getSystemService(NotificationManager::class.java)
            val filter = notificationManager?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            notificationDnd = filter >= NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            notificationDnd = false
        }
        val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        try {
            return if (treatVibrateAsSilent()) {
                notificationDnd || ringerMode != AudioManager.RINGER_MODE_NORMAL
            } else {
                notificationDnd || ringerMode == AudioManager.RINGER_MODE_SILENT
            }
        } catch (throwable: Throwable) {
            Timber.d("platform bug in isPhoneSilenced (${throwable.message})")
            return notificationDnd
        }

    }

}

@ActivityScope
class Preferences @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(service.applicationContext)
}

@ActivityScope
class AutomaticMessageDeletionDate @Inject constructor(
    private val getLongPreference: GetLongPreference
) {
    operator fun invoke(): Long {
        val timeout = getLongPreference(
            SettingsActivity.AUTOMATIC_MESSAGE_DELETION,
            R.integer.automatic_message_deletion
        )
        return if (timeout == 0L) timeout else System.currentTimeMillis() - timeout * 1000
    }

//we only want to show this when we type a e164 number
}

@ActivityScope
class KnownHosts @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): Collection<String> {
        val hosts = HashSet<String>()
        for (account in service.accounts!!) {
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

}

@ActivityScope
class KnownConferenceHosts @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): Collection<String> {
        val mucServers = HashSet<String>()
        for (account in service.accounts!!) {
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
}


@ServiceScope
class IsInLowPingTimeoutMode @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(account: Account): Boolean = service.run {
        synchronized(mLowPingTimeoutMode) {
            return mLowPingTimeoutMode.contains(account.jid.asBareJid())
        }
    }
}

@ServiceScope
class StartForcingForegroundNotification @Inject constructor(
    private val service: XmppConnectionService,
    private val toggleForegroundService: ToggleForegroundService
) {
    operator fun invoke() {
        service.mForceForegroundService.set(true)
        toggleForegroundService()
    }
}

@ServiceScope
class StopForcingForegroundNotification @Inject constructor(
    private val service: XmppConnectionService,
    private val toggleForegroundService: ToggleForegroundService
) {
    operator fun invoke() {
        service.mForceForegroundService.set(false)
        toggleForegroundService()
    }
}

@ServiceScope
class AreMessagesInitialized @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): Boolean {
        return service.restoredFromDatabaseLatch.count == 0L
    }
}

@ServiceScope
class AttachLocationToConversation @Inject constructor(
    private val pgpEngine: PgpEngine,
    private val sendMessage: SendMessage
) {
    operator fun invoke(
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
            pgpEngine.encrypt(message, callback)
        } else {
            sendMessage(message)
            callback.success(message)
        }
    }
}

@ServiceScope
class AttachFileToConversation @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(
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
        val runnable = AttachFileToConversationRunnable(service, uri, type, message, callback)
        if (runnable.isVideoMessage()) {
            service.mVideoCompressionExecutor.execute(runnable)
        } else {
            service.mFileAddingExecutor.execute(runnable)
        }
    }
}

@ServiceScope
class AttachImageToConversation @Inject constructor(
    private val service: XmppConnectionService,
    private val fileBackend: FileBackend,
    private val attachFileToConversation: AttachFileToConversation,
    private val sendMessage: SendMessage
) {
    operator fun invoke(
        conversation: Conversation,
        uri: Uri,
        callback: UiCallback<Message>?
    ) {
        val mimeType = MimeUtils.guessMimeTypeFromUri(service, uri)
        val compressPictures = service.compressPicturesPreference

        if ("never" == compressPictures
            || "auto" == compressPictures && service.fileBackend.useImageAsIs(uri)
            || mimeType != null && mimeType.endsWith("/gif")
            || fileBackend.unusualBounds(uri)
        ) {
            Timber.d(conversation.account.jid.asBareJid().toString() + ": not compressing picture. sending as file")
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
        service.mFileAddingExecutor.execute {
            try {
                fileBackend.copyImageToPrivateStorage(message, uri)
                if (conversation.nextEncryption == Message.ENCRYPTION_PGP) {
                    val pgpEngine = service.pgpEngine
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
}

@ServiceScope
class Find @Inject constructor(
    private val getConversations: GetConversations
) {
    operator fun invoke(bookmark: Bookmark): Conversation? {
        return invoke(bookmark.account, bookmark.jid)
    }

    operator fun invoke(account: Account, jid: Jid?): Conversation? {
        return invoke(getConversations(), account, jid)
    }

    operator fun invoke(haystack: Iterable<Conversation>, contact: Contact): Conversation? {
        for (conversation in haystack) {
            if (conversation.contact === contact) {
                return conversation
            }
        }
        return null
    }

    operator fun invoke(
        haystack: Iterable<Conversation>,
        account: Account?,
        jid: Jid?
    ): Conversation? {
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
}


@ServiceScope
class IsMuc @Inject constructor(
    private val find: Find
) {
    operator fun invoke(account: Account, jid: Jid): Boolean {
        val c = find(account, jid)
        return c != null && c.mode == Conversational.MODE_MULTI
    }
}

@ServiceScope
class Search @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(term: List<String>, onSearchResultsAvailable: OnSearchResultsAvailable) {
        MessageSearchTask.search(service, term, onSearchResultsAvailable)
    }
}


@TargetApi(Build.VERSION_CODES.M)
class ScheduleNextIdlePing(
    private val service: XmppConnectionService
) {
    operator fun invoke() {
        val timeToWake = SystemClock.elapsedRealtime() + Config.IDLE_PING_INTERVAL * 1000
        val alarmManager =
            service.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(service, EventReceiver::class.java)
        intent.action = XmppConnectionService.ACTION_IDLE_PING
        try {
            val pendingIntent = PendingIntent.getBroadcast(service, 0, intent, 0)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                timeToWake,
                pendingIntent
            )
        } catch (e: RuntimeException) {
            Timber.d(e, "unable to schedule alarm for idle ping")
        }
    }
}

@ServiceScope
class OnStartCommand @Inject constructor(
    private val service: XmppConnectionService,
    private val toggleForegroundService: ToggleForegroundService,
    private val hasInternetConnection: HasInternetConnection,
    private val schedulePostConnectivityChange: SchedulePostConnectivityChange,
    private val resetAllAttemptCounts: ResetAllAttemptCounts,
    private val logoutAndSave: LogoutAndSave,
    private val notificationService: NotificationService,
    private val findConversationByUuid: FindConversationByUuid,
    private val dismissErrorNotifications: DismissErrorNotifications,
    private val directReply: DirectReply,
    private val sendReadMarker: SendReadMarker,
    private val updateConversation: UpdateConversation,
    private val dndOnSilentMode: DndOnSilentMode,
    private val refreshAllPresences: RefreshAllPresences,
    private val deactivateGracePeriod: DeactivateGracePeriod,
    private val awayWhenScreenOff: AwayWhenScreenOff,
    private val refreshAllFcmTokens: RefreshAllFcmTokens,
    private val scheduleNextIdlePing: ScheduleNextIdlePing,
    private val processAccountState: ProcessAccountState,
    private val isInLowPingTimeoutMode: IsInLowPingTimeoutMode,
    private val scheduleWakeUpCall: ScheduleWakeUpCall,
    private val expireOldMessages: ExpireOldMessages

) {
    operator fun invoke(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val needsForegroundService = intent != null && intent.getBooleanExtra(
            EventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE,
            false
        )
        if (needsForegroundService) {
            Timber.d("toggle forced foreground service after receiving event (action=$action)")
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
                XmppConnvectionConstans.ACTION_CLEAR_NOTIFICATION -> service.mNotificationExecutor.execute {
                    try {
                        val c = findConversationByUuid(uuid)
                        if (c != null) {
                            notificationService.clear(c)
                        } else {
                            notificationService.clear()
                        }
                        service.restoredFromDatabaseLatch.await()

                    } catch (e: InterruptedException) {
                        Timber.d("unable to process clear notification")
                    }
                }
                XmppConnvectionConstans.ACTION_DISMISS_ERROR_NOTIFICATIONS -> dismissErrorNotifications()
                XmppConnvectionConstans.ACTION_TRY_AGAIN -> {
                    resetAllAttemptCounts(false, true)
                    interactive = true
                }
                XmppConnvectionConstans.ACTION_REPLY_TO_CONVERSATION -> {
                    RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence("text_reply")
                        ?.takeIf(CharSequence::isNotEmpty)
                        ?.let { body ->
                            service.mNotificationExecutor.execute {
                                try {
                                    service.restoredFromDatabaseLatch.await()
                                    val c = findConversationByUuid(uuid)
                                    if (c != null) {
                                        val dismissNotification =
                                            intent.getBooleanExtra(
                                                "dismiss_notification",
                                                false
                                            )
                                        directReply(c, body.toString(), dismissNotification)
                                    }
                                } catch (e: InterruptedException) {
                                    Timber.d("unable to process direct reply")
                                }
                            }
                        }
                }
                XmppConnvectionConstans.ACTION_MARK_AS_READ -> service.mNotificationExecutor.execute {
                    val c = findConversationByUuid(uuid)
                    if (c == null) {
                        Timber.d("received mark read intent for unknown conversation ($uuid)")
                        return@execute
                    }
                    try {
                        service.restoredFromDatabaseLatch.await()
                        sendReadMarker(c, null)
                    } catch (e: InterruptedException) {
                        Timber.d("unable to process notification read marker for conversation ${c?.name}")
                    }


                }
                XmppConnvectionConstans.ACTION_SNOOZE -> {
                    service.mNotificationExecutor.execute {
                        val c = findConversationByUuid(uuid)
                        if (c == null) {
                            Timber.d("received snooze intent for unknown conversation ($uuid)")
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
                XmppConnvectionConstans.ACTION_FCM_TOKEN_REFRESH -> refreshAllFcmTokens()
                XmppConnvectionConstans.ACTION_IDLE_PING -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    scheduleNextIdlePing()
                }
                XmppConnvectionConstans.ACTION_FCM_MESSAGE_RECEIVED -> {
                    pushedAccountHash = intent.getStringExtra("account")
                    Timber.d("push message arrived in service. account=$pushedAccountHash")
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
        synchronized(service) {
            WakeLockHelper.acquire(service.wakeLock)
            var pingNow =
                ConnectivityManager.CONNECTIVITY_ACTION == action || Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0 && XmppConnvectionConstans.ACTION_POST_CONNECTIVITY_CHANGE == action
            val pingCandidates = HashSet<Account>()
            for (account in service.accounts!!) {
                pingNow = pingNow or processAccountState(
                    account,
                    interactive,
                    "ui" == action,
                    CryptoHelper.getAccountFingerprint(
                        account,
                        PhoneHelper.getAndroidId(service)
                    ) == pushedAccountHash,
                    pingCandidates
                )
            }
            if (pingNow) {
                for (account in pingCandidates) {
                    val lowTimeout = isInLowPingTimeoutMode(account)
                    account.xmppConnection.sendPing()
                    Timber.d("${account.jid.asBareJid()} send ping (action=$action, lowTimeout=$lowTimeout)")
                    scheduleWakeUpCall(
                        if (lowTimeout) Config.LOW_PING_TIMEOUT else Config.PING_TIMEOUT,
                        account.uuid.hashCode()
                    )
                }
            }
            WakeLockHelper.release(service.wakeLock)
        }
        if (SystemClock.elapsedRealtime() - service.mLastExpiryRun.get() >= Config.EXPIRY_INTERVAL) {
            expireOldMessages()
        }
        return Service.START_STICKY
    }
}


@ServiceScope
class ProcessAccountState @Inject constructor(
    private val service: XmppConnectionService,
    private val statusListener: StatusListener,
    private val hasInternetConnection: HasInternetConnection,
    private val reconnectAccount: ReconnectAccount,
    private val scheduleWakeUpCall: ScheduleWakeUpCall
) {
    operator fun invoke(
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
                statusListener.onStatusChanged(account)
            } else {
                if (account.status == Account.State.NO_INTERNET) {
                    account.status = Account.State.OFFLINE
                    statusListener.onStatusChanged(account)
                }
                if (account.status == Account.State.ONLINE) {
                    synchronized(service.mLowPingTimeoutMode) {
                        val lastReceived = account.xmppConnection.lastPacketReceived
                        val lastSent = account.xmppConnection.lastPingSent
                        val pingInterval =
                            (if (isUiAction) Config.PING_MIN_INTERVAL * 1000 else Config.PING_MAX_INTERVAL * 1000).toLong()
                        val msToNextPing = Math.max(
                            lastReceived,
                            lastSent
                        ) + pingInterval - SystemClock.elapsedRealtime()
                        val pingTimeout =
                            if (service.mLowPingTimeoutMode.contains(account.jid.asBareJid())) Config.LOW_PING_TIMEOUT * 1000 else Config.PING_TIMEOUT * 1000
                        val pingTimeoutIn = lastSent + pingTimeout - SystemClock.elapsedRealtime()
                        if (lastSent > lastReceived) {
                            if (pingTimeoutIn < 0) {
                                Timber.d(account.jid.asBareJid().toString() + ": ping timeout")
                                service.reconnectAccount(account, true, interactive)
                            } else {
                                val secs = (pingTimeoutIn / 1000).toInt()
                                service.scheduleWakeUpCall(secs, account.uuid.hashCode())
                            }
                        } else {
                            pingCandidates.add(account)
                            if (isAccountPushed) {
                                pingNow = true
                                if (service.mLowPingTimeoutMode.add(account.jid.asBareJid())) {
                                    Timber.d(account.jid.asBareJid().toString() + ": entering low ping timeout mode")
                                }
                            } else if (msToNextPing <= 0) {
                                pingNow = true
                            } else {
                                service.scheduleWakeUpCall(
                                    (msToNextPing / 1000).toInt(),
                                    account.uuid.hashCode()
                                )
                                if (service.mLowPingTimeoutMode.remove(account.jid.asBareJid())) {
                                    Timber.d(account.jid.asBareJid().toString() + ": leaving low ping timeout mode")
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
                        Timber.d(account.jid.toString() + ": time out during connect reconnecting (secondsSinceLast=" + secondsSinceLastConnect + ")")
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
}

@ServiceScope
class DiscoverChannels @Inject constructor(
    private val service: XmppConnectionService,
    private val discoverChannelsInternal: DiscoverChannelsInternal
) {
    operator fun invoke(
        query: String?,
        onChannelSearchResultsFound: XmppConnectionService.OnChannelSearchResultsFound
    ) {
        Timber.d("discover channels. query=" + query!!)
        if (query == null || query.trim { it <= ' ' }.isEmpty()) {
            discoverChannelsInternal(onChannelSearchResultsFound)
        } else {
            discoverChannelsInternal(query, onChannelSearchResultsFound)
        }
    }
}

@ServiceScope
class DiscoverChannelsInternal @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(listener: XmppConnectionService.OnChannelSearchResultsFound) {
        val call = service.muclumbusService!!.getRooms(1)
        try {
            call.enqueue(object : Callback<MuclumbusService.Rooms> {
                override fun onResponse(
                    call: Call<MuclumbusService.Rooms>,
                    response: Response<MuclumbusService.Rooms>
                ) {
                    val body = response.body() ?: return
                    listener.onChannelSearchResultsFound(body.items)
                }

                override fun onFailure(
                    call: Call<MuclumbusService.Rooms>,
                    throwable: Throwable
                ) {

                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }
    operator fun invoke(query: String, listener: XmppConnectionService.OnChannelSearchResultsFound) {
        val searchResultCall = service.muclumbusService!!.search(MuclumbusService.SearchRequest(query))

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
}

@ServiceScope
class DirectReply @Inject constructor(
    private val pgpEngine: eu.siacs.conversations.feature.xmppconnection.PgpEngine,
    private val markRead: MarkRead,
    private val notificationService: NotificationService,
    private val sendMessage: SendMessage
) {
    operator fun invoke(conversation: Conversation, body: String, dismissAfterReply: Boolean) {
        val message = Message(conversation, body, conversation.nextEncryption)
        message.markUnread()
        if (message.encryption == Message.ENCRYPTION_PGP) {
            pgpEngine()!!.encrypt(message, object : UiCallback<Message> {
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
}

@ServiceScope
class DndOnSilentMode @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference(
            SettingsActivity.DND_ON_SILENT_MODE,
            R.bool.dnd_on_silent_mode
        )
    }
}

@ServiceScope
class ManuallyChangePresence @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference(
            SettingsActivity.MANUALLY_CHANGE_PRESENCE,
            R.bool.manually_change_presence
        )
    }
}

@ServiceScope
class TreatVibrateAsSilent @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference(
            SettingsActivity.TREAT_VIBRATE_AS_SILENT,
            R.bool.treat_vibrate_as_silent
        )
    }
}

@ServiceScope
class AwayWhenScreenOff @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference(
            SettingsActivity.AWAY_WHEN_SCREEN_IS_OFF,
            R.bool.away_when_screen_off
        )
    }
}

@ServiceScope
class ResetAllAttemptCounts @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val notificationService: NotificationService
) {
    operator fun invoke(reallyAll: Boolean, retryImmediately: Boolean) {
        Timber.d("resetting all attempt counts")
        for (account in service.accounts!!) {
            if (account.hasErrorStatus() || reallyAll) {
                val connection = account.xmppConnection
                connection?.resetAttemptCount(retryImmediately)
            }
            if (account.setShowErrorNotification(true)) {
                service.mDatabaseWriterExecutor.execute { databaseBackend.updateAccount(account) }
            }
        }
        notificationService.updateErrorNotification()
    }
}

@ServiceScope
class DismissErrorNotifications @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend
) {
    operator fun invoke() {
        for (account in service.accounts!!) {
            if (account.hasErrorStatus()) {
                Timber.d(account.jid.asBareJid().toString() + ": dismissing error notification")
                if (account.setShowErrorNotification(false)) {
                    service.mDatabaseWriterExecutor.execute { databaseBackend.updateAccount(account) }
                }
            }
        }
    }
}

@ServiceScope
class ExpireOldMessages @Inject constructor(
    private val service: XmppConnectionService,
    private val automaticMessageDeletionDate: AutomaticMessageDeletionDate,
    private val databaseBackend: DatabaseBackend,
    private val updateConversationUi: UpdateConversationUi
) {

    @JvmOverloads
    operator fun invoke(resetHasMessagesLeftOnServer: Boolean = false) {
        service.mLastExpiryRun.set(SystemClock.elapsedRealtime())
        service.mDatabaseWriterExecutor.execute {
            val timestamp = automaticMessageDeletionDate()
            if (timestamp > 0) {
                databaseBackend.expireOldMessages(timestamp)
                synchronized(service.conversations) {
                    for (conversation in service.conversations) {
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
}


@ServiceScope
class HasInternetConnection @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): Boolean {
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            val activeNetwork = cm?.activeNetworkInfo
            return activeNetwork != null && (activeNetwork.isConnected || activeNetwork.type == ConnectivityManager.TYPE_ETHERNET)
        } catch (e: RuntimeException) {
            Timber.d(e, "unable to check for internet connection")
            return true //if internet connection can not be checked it is probably best to just try
        }

    }
}

@ServiceScope
class OnCreate @Inject constructor(
    private val service: XmppConnectionService,
    private val notificationService: NotificationService,
    private val toggleForegroundService: ToggleForegroundService,
    private val updateMemorizingTrustmanager: UpdateMemorizingTrustmanager,
    private val preferences: SharedPreferences,
    private val databaseBackend: DatabaseBackend,
    private val hasEnabledAccounts: HasEnabledAccounts,
    private val toggleSetProfilePictureActivity: ToggleSetProfilePictureActivity,
    private val restoreFromDatabase: RestoreFromDatabase,
    private val startContactObserver: StartContactObserver,
    private val updateUnreadCountBadge: UpdateUnreadCountBadge,
    private val toggleScreenEventReceiver: ToggleScreenEventReceiver,
    private val scheduleNextIdlePing: ScheduleNextIdlePing
) {

    @SuppressLint("TrulyRandom")
    operator fun invoke() {
        if (Compatibility.runsTwentySix()) {
            notificationService.initializeChannels()
        }
        service.mForceDuringOnCreate.set(Compatibility.runsAndTargetsTwentySix(service))
        toggleForegroundService()
        service.destroyed = false
        OmemoSetting.load(service)
        ExceptionHelper.init(service.applicationContext)
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (throwable: Throwable) {
            Timber.e(throwable, "unable to initialize security provider")
        }

        Resolver.init(service)
        service.rng = SecureRandom()
        updateMemorizingTrustmanager()
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        service.bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
        if (service.mLastActivity == 0L) {
            service.mLastActivity = preferences.getLong(XmppConnvectionConstans.SETTING_LAST_ACTIVITY_TS, System.currentTimeMillis())
        }

        Timber.d("initializing database...")
        service.databaseBackend = DatabaseBackend.getInstance(service.applicationContext)
        Timber.d("restoring accounts...")
        service.accounts = databaseBackend.accounts
        val editor = preferences.edit()
        if (service.accounts!!.size == 0 && Arrays.asList(
                "Sony",
                "Sony Ericsson"
            ).contains(Build.MANUFACTURER)
        ) {
            editor.putBoolean(SettingsActivity.KEEP_FOREGROUND_SERVICE, true)
            Timber.d(Build.MANUFACTURER + " is on blacklist. enabling foreground service")
        }
        val hasEnabledAccounts = hasEnabledAccounts()
        editor.putBoolean(EventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts).apply()
        editor.apply()
        toggleSetProfilePictureActivity(hasEnabledAccounts)

        restoreFromDatabase()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ContextCompat.checkSelfPermission(
                service,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startContactObserver()
        }
        if (Compatibility.hasStoragePermission(service)) {
            Timber.d("starting file observer")
            service.mFileAddingExecutor.execute { service.fileObserver.startWatching() }
            service.mFileAddingExecutor.execute { service.checkForDeletedFiles() }
        }
        if (Config.supportOpenPgp()) {
            service.pgpServiceConnection = OpenPgpServiceConnection(
                service,
                "org.sufficientlysecure.keychain",
                object : OpenPgpServiceConnection.OnBound {
                    override fun onBound(service: IOpenPgpService2) {
                        for (account in this@OnCreate.service.accounts!!) {
                            val pgp = account.pgpDecryptionService
                            pgp?.continueDecryption(true)
                        }
                    }

                    override fun onError(e: Exception) {}
                })
            service.pgpServiceConnection!!.bindToService()
        }

        (service.getSystemService(Context.POWER_SERVICE) as PowerManager).let { powerManager ->
            service.pm = powerManager
            service.wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Conversations:Service")
        }

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
            service.registerReceiver(service.mInternalEventReceiver, intentFilter)
        }
        service.mForceDuringOnCreate.set(false)
        toggleForegroundService()

        val retrofit = Retrofit.Builder()
            .baseUrl(Config.CHANNEL_DISCOVERY)
            .addConverterFactory(GsonConverterFactory.create())
            .callbackExecutor(Executors.newSingleThreadExecutor())
            .build()

        service.muclumbusService = retrofit.create(MuclumbusService::class.java)
    }
}

@ServiceScope
class CheckForDeletedFiles @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val fileBackend: FileBackend,
    private val markChangedFiles: MarkChangedFiles
) {
    operator fun invoke() {
        if (service.destroyed) {
            Timber.d("Do not check for deleted files because service has been destroyed")
            return
        }
        val start = SystemClock.elapsedRealtime()
        val relativeFilePaths = databaseBackend.filePathInfo
        val changed = ArrayList<DatabaseBackend.FilePathInfo>()
        for (filePath in relativeFilePaths) {
            if (service.destroyed) {
                Timber.d("Stop checking for deleted files because service has been destroyed")
                return
            }
            val file = fileBackend.getFileForPath(filePath.path)
            if (filePath.setDeleted(!file.exists())) {
                changed.add(filePath)
            }
        }
        val duration = SystemClock.elapsedRealtime() - start
        Timber.d("found ${changed.size} changed files on start up. total=${relativeFilePaths.size}. (${duration}ms)")
        if (changed.size > 0) {
            databaseBackend.markFilesAsChanged(changed)
            markChangedFiles(changed)
        }
    }
}

@ServiceScope
class StartContactObserver @Inject constructor(
    private val service: XmppConnectionService,
    private val contentResolver: ContentResolver,
    private val loadPhoneContacts: LoadPhoneContacts
) {
    operator fun invoke() {
        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    if (service.restoredFromDatabaseLatch.count == 0L) {
                        loadPhoneContacts()
                    }
                }
            })
    }
}


@ServiceScope
class FetchRosterFromServer @Inject constructor(
    private val sendIqPacket: SendIqPacket,
    private val iqParser: IqParser
) {
    operator fun invoke(account: Account) {
        val iqPacket = IqPacket(IqPacket.TYPE.GET)
        if ("" != account.rosterVersion) {
            Timber.d(account.jid.asBareJid().toString() + ": fetching roster version " + account.rosterVersion)
        } else {
            Timber.d(account.jid.asBareJid().toString() + ": fetching roster")
        }
        iqPacket.query(Namespace.ROSTER).setAttribute("ver", account.rosterVersion)
        sendIqPacket(account, iqPacket, iqParser)
    }
}

@ServiceScope
class FetchBookmarks @Inject constructor(
    private val processBookmarks: ProcessBookmarks,
    private val sendIqPacket: SendIqPacket
) {
    operator fun invoke(account: Account) {
        val iqPacket = IqPacket(IqPacket.TYPE.GET)
        val query = iqPacket.query("jabber:iq:private")
        query.addChild("storage", Namespace.BOOKMARKS)
        val callback = OnIqPacketReceived { a, response ->
            if (response.getType() == IqPacket.TYPE.RESULT) {
                val query1 = response.query()
                val storage = query1.findChild("storage", "storage:bookmarks")
                processBookmarks(a, storage, false)
            } else {
                Timber.d("${a.jid.asBareJid()}: could not fetch bookmarks")
            }
        }
        sendIqPacket(account, iqPacket, callback)
    }
}

@ServiceScope
class ProcessBookmarks @Inject constructor(
    private val service: XmppConnectionService,
    private val synchronizeWithBookmarks: SynchronizeWithBookmarks,
    private val find: Find,
    private val archiveConversation: ArchiveConversation,
    private val findOrCreateConversation: FindOrCreateConversation
) {
    operator fun invoke(account: Account, storage: Element?, pep: Boolean) {
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
                            Timber.d("${account.jid.asBareJid()}: archiving conference (${conversation.jid}) after receiving pep")
                            archiveConversation(conversation, false)
                        }
                    } else if (synchronizeWithBookmarks && bookmark.autojoin()) {
                        conversation =
                            findOrCreateConversation(
                                account,
                                bookmark.fullJid,
                                true,
                                true,
                                false
                            )
                        bookmark.conversation = conversation
                    }
                }
            }
            if (pep && synchronizeWithBookmarks) {
                Timber.d("${account.jid.asBareJid()}: ${previousBookmarks.size} bookmarks have been removed")
                for (jid in previousBookmarks) {
                    val conversation = find(account, jid)
                    if (conversation != null && conversation.mucOptions.error == MucOptions.Error.DESTROYED) {
                        Timber.d("${account.jid.asBareJid()}: archiving destroyed conference (${conversation.jid}) after receiving pep")
                        archiveConversation(conversation, false)
                    }
                }
            }
        }
        account.setBookmarks(CopyOnWriteArrayList(bookmarks.values))
    }
}

@ServiceScope
class PushBookmarks @Inject constructor(
    private val pushBookmarksPep: PushBookmarksPep,
    private val pushBookmarksPrivateXml: PushBookmarksPrivateXml
) {
    operator fun invoke(account: Account) {
        if (account.xmppConnection.features.bookmarksConversion()) {
            pushBookmarksPep(account)
        } else {
            pushBookmarksPrivateXml(account)
        }
    }
}

@ServiceScope
class PushBookmarksPrivateXml @Inject constructor(
    private val sendIqPacket: SendIqPacket,
    private val defaultIqHandler: DefaultIqHandler
) {
    operator fun invoke(account: Account) {
        Timber.d("${account.jid.asBareJid()}: pushing bookmarks via xml")
        val iqPacket = IqPacket(IqPacket.TYPE.SET)
        val query = iqPacket.query("jabber:iq:private")
        val storage = query.addChild("storage", "torage:bookmarks")
        for (bookmark in account.bookmarks) {
            storage.addChild(bookmark)
        }
        sendIqPacket(account, iqPacket, defaultIqHandler)
    }
}

@ServiceScope
class PushBookmarksPep @Inject constructor(
    private val pushNodeAndEnforcePublishOptions: PushNodeAndEnforcePublishOptions
) {
    operator fun invoke(account: Account) {
        Timber.d("${account.jid.asBareJid()}: pushing bookmarks via pep")
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
}

@ServiceScope
class PushNodeAndEnforcePublishOptions @Inject constructor(
    private val iqGenerator: IqGenerator,
    private val sendIqPacket: SendIqPacket,
    private val pushNodeConfiguration: PushNodeConfiguration,
    private val pushNodeAndEnforcePublishOptions: PushNodeAndEnforcePublishOptions
) {
    operator fun invoke(
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
                pushNodeConfiguration(account, node, options, object :
                    XmppConnectionService.OnConfigurationPushed {
                    override fun onPushSucceeded() {
                        pushNodeAndEnforcePublishOptions(account, node, element, options, false)
                    }

                    override fun onPushFailed() {
                        Timber.d("${account.jid.asBareJid()}: unable to push node configuration ($node)")
                    }
                })
            } else {
                Timber.d("${account.jid.asBareJid()}: error publishing bookmarks (retry=$retry) $response")
            }
        })
    }
}

@ServiceScope
class RestoreFromDatabase @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val loadPhoneContacts: LoadPhoneContacts,
    private val restoreMessages: RestoreMessages,
    private val updateConversationUi: UpdateConversationUi,
    private val notificationService: NotificationService
) {
    operator fun invoke() {
        synchronized(service.conversations) {
            val accountLookupTable = Hashtable<String, Account>()
            for (account in service.accounts!!) {
                accountLookupTable[account.uuid] = account
            }
            Timber.d("restoring conversations...")
            val startTimeConversationsRestore = SystemClock.elapsedRealtime()
            service.conversations.addAll(databaseBackend.getConversations(Conversation.STATUS_AVAILABLE))
            val iterator = service.conversations.listIterator()
            while (iterator.hasNext()) {
                val conversation = iterator.next()
                val account = accountLookupTable[conversation.accountUuid]
                if (account != null) {
                    conversation.account = account
                } else {
                    Timber.e("unable to restore Conversations with ${conversation.jid}")
                    iterator.remove()
                }
            }
            val diffConversationsRestore =
                SystemClock.elapsedRealtime() - startTimeConversationsRestore
            Timber.d("finished restoring conversations in ${diffConversationsRestore}ms")
            val runnable = Runnable {
                val deletionDate = service.automaticMessageDeletionDate
                service.mLastExpiryRun.set(SystemClock.elapsedRealtime())
                if (deletionDate > 0) {
                    Timber.d(
                        "deleting messages that are older than " + AbstractGenerator.getTimestamp(
                            deletionDate
                        )
                    )
                    databaseBackend.expireOldMessages(deletionDate)
                }
                Timber.d("restoring roster...")
                for (account in service.accounts!!) {
                    databaseBackend.readRoster(account.roster)
                    account.initAccountServices(service) //roster needs to be loaded at service stage
                }
                service.bitmapCache!!.evictAll()
                loadPhoneContacts()
                Timber.d("restoring messages...")
                val startMessageRestore = SystemClock.elapsedRealtime()
                val quickLoad = QuickLoader.get(service.conversations)
                if (quickLoad != null) {
                    restoreMessages(quickLoad)
                    updateConversationUi()
                    val diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore
                    Timber.d("quickly restored " + quickLoad.name + " after " + diffMessageRestore + "ms")
                }
                for (conversation in service.conversations) {
                    if (quickLoad !== conversation) {
                        restoreMessages(conversation)
                    }
                }
                notificationService.finishBacklog(false)
                service.restoredFromDatabaseLatch.countDown()
                val diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore
                Timber.d("finished restoring messages in " + diffMessageRestore + "ms")
                updateConversationUi()
            }
            service.mDatabaseReaderExecutor.execute(runnable) //will contain one write command (expiry) but that's fine
        }
    }
}

@ServiceScope
class RestoreMessages @Inject constructor(
    private val databaseBackend: DatabaseBackend,
    private val markMessage: MarkMessage,
    private val notificationService: NotificationService
) {
    operator fun invoke(conversation: Conversation) {
        conversation.addAll(0, databaseBackend.getMessages(conversation, Config.PAGE_SIZE))
        conversation.findUnsentTextMessages { message ->
            markMessage(
                message,
                Message.STATUS_WAITING
            )
        }
        conversation.findUnreadMessages { message -> notificationService.pushFromBacklog(message) }
    }
}

@ServiceScope
class LoadPhoneContacts @Inject constructor(
    private val service: XmppConnectionService,
    private val avatarService: AvatarService,
    private val shortcutService: ShortcutService,
    private val updateRosterUi: UpdateRosterUi,
    private val quickConversationsService: QuickConversationsService
) {
    operator fun invoke() {
        service.mContactMergerExecutor.execute {
            val contacts = JabberIdContact.load(service)
            Timber.d("start merging phone contacts with roster")
            for (account in service.accounts!!) {
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
            shortcutService.refresh(
                service.mInitialAddressbookSyncCompleted.compareAndSet(
                    false,
                    true
                )
            )
            updateRosterUi()
            quickConversationsService.considerSync()
        }
    }

}

@ServiceScope
class SyncRoster @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend
) {
    operator fun invoke(account: Account) {
        service.mRosterSyncTaskManager.execute(account) { databaseBackend.writeRoster(account.roster) }
    }
}

@ServiceScope
class GetConversations @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): List<Conversation> {
        return service.conversations
    }
}

@ServiceScope
class MarkFileDeleted @Inject constructor(
    private val fileBackend: FileBackend,
    private val databaseBackend: DatabaseBackend,
    private val markUuidsAsDeletedFiles: MarkUuidsAsDeletedFiles
) {
    operator fun invoke(path: String) {
        val file = File(path)
        val isInternalFile = fileBackend.isInternalFile(file)
        val uuids = databaseBackend.markFileAsDeleted(file, isInternalFile)
        Timber.d("deleted file $path internal=$isInternalFile, database hits=${uuids.size}")
        markUuidsAsDeletedFiles(uuids)
    }
}

@ServiceScope
class MarkUuidsAsDeletedFiles @Inject constructor(
    private val getConversations: GetConversations,
    private val updateConversationUi: UpdateConversationUi
) {
    operator fun invoke(uuids: List<String>) {
        var deleted = false
        for (conversation in getConversations()) {
            deleted = deleted or conversation.markAsDeleted(uuids)
        }
        if (deleted) {
            updateConversationUi()
        }
    }
}

@ServiceScope
class MarkChangedFiles @Inject constructor(
    private val getConversations: GetConversations,
    private val updateConversationUi: UpdateConversationUi
) {
    operator fun invoke(infos: List<DatabaseBackend.FilePathInfo>) {
        var changed = false
        for (conversation in getConversations()) {
            changed = changed or conversation.markAsChanged(infos)
        }
        if (changed) {
            updateConversationUi()
        }
    }
}

@ServiceScope
class PopulateWithOrderedConversations @Inject constructor(
    private val getConversations: GetConversations
) {
    @JvmOverloads
    operator fun invoke(
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
                list.sortWith(Comparator { a, b ->
                    val indexA = orderedUuids.indexOf(a.uuid)
                    val indexB = orderedUuids.indexOf(b.uuid)
                    if (indexA == -1 || indexB == -1 || indexA == indexB)
                        a.compareTo(b)
                    else
                        indexA - indexB
                })
            } else {
                list.sort()
            }
        } catch (e: IllegalArgumentException) {
            //ignore
        }

    }
}

@ServiceScope
class LoadMoreMessages @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val messageArchiveService: MessageArchiveService
) {
    operator fun invoke(
        conversation: Conversation,
        timestamp: Long,
        callback: XmppConnectionService.OnMoreMessagesLoaded
    ) {
        if (service.messageArchiveService.queryInProgress(
                conversation,
                callback
            )
        ) {
            return
        } else if (timestamp == 0L) {
            return
        }
        Timber.d(
            "load more messages for %s prior to %s",
            conversation.name,
            MessageGenerator.getTimestamp(timestamp)
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
        service.mDatabaseReaderExecutor.execute(runnable)
    }
}


/**
 * This will find all conferences with the contact as member and also the conference that is the contact (that 'fake' contact is used to store the avatar)
 */
@ServiceScope
class FindAllConferencesWith @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(contact: Contact): List<Conversation> {
        val results = ArrayList<Conversation>()
        for (c in service.conversations) {
            if (c.mode == Conversation.MODE_MULTI && (c.jid.asBareJid() == contact.jid.asBareJid() || c.mucOptions.isContactInRoom(
                    contact
                ))
            ) {
                results.add(c)
            }
        }
        return results
    }
}

@ServiceScope
class IsConversationsListEmpty @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(ignore: Conversation?): Boolean {
        synchronized(service.conversations) {
            val size = service.conversations.size
            return size == 0 || size == 1 && service.conversations[0] === ignore
        }
    }
}

@ServiceScope
class IsConversationStillOpen @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(conversation: Conversation): Boolean {
        synchronized(service.conversations) {
            for (current in service.conversations) {
                if (current === conversation) {
                    return true
                }
            }
        }
        return false
    }
}


@ServiceScope
class OnTrimMemory @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(level: Int) {
//        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            Timber.d("clear cache due to low memory")
            service.bitmapCache!!.evictAll()
        }
    }
}

@ServiceScope
class OnDestroy @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke() {
        try {
            service.unregisterReceiver(service.mInternalEventReceiver)
        } catch (e: IllegalArgumentException) {
            //ignored
        }

        service.destroyed = false
        service.fileObserver.stopWatching()
//        super.onDestroy()
    }
}

@ServiceScope
class RestartFileObserver @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke() {
        Timber.d("restarting file observer")
        service.mFileAddingExecutor.execute { service.fileObserver.restartWatching() }
        service.mFileAddingExecutor.execute { service.checkForDeletedFiles() }
    }
}

@ServiceScope
class ToggleScreenEventReceiver @Inject constructor(
    private val service: XmppConnectionService,
    private val awayWhenScreenOff: AwayWhenScreenOff,
    private val manuallyChangePresence: ManuallyChangePresence
) {
    operator fun invoke() {
        if (awayWhenScreenOff() && !manuallyChangePresence()) {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            service.registerReceiver(service.mInternalScreenEventReceiver, filter)
        } else {
            try {
                service.unregisterReceiver(service.mInternalScreenEventReceiver)
            } catch (e: IllegalArgumentException) {
                //ignored
            }

        }
    }
}

@ServiceScope
class ToggleForegroundService @Inject constructor(
    private val service: XmppConnectionService,
    private val hasEnabledAccounts: HasEnabledAccounts,
    private val notificationService: NotificationService
) {

    @JvmOverloads
    operator fun invoke(force: Boolean = false) {
        val status: Boolean
        if (force || service.mForceDuringOnCreate.get() || service.mForceForegroundService.get() || Compatibility.keepForegroundService(
                service
            ) && hasEnabledAccounts()
        ) {
            val notification = service.notificationService.createForegroundNotification()
            service.startForeground(NotificationService.FOREGROUND_NOTIFICATION_ID, notification)
            if (!service.mForceForegroundService.get()) {
                service.notificationService.notify(
                    NotificationService.FOREGROUND_NOTIFICATION_ID,
                    notification
                )
            }
            status = true
        } else {
            service.stopForeground(true)
            status = false
        }
        if (!service.mForceForegroundService.get()) {
            notificationService.dismissForcedForegroundNotification() //if the channel was changed the previous call might fail
        }
        Timber.d("ForegroundService: ${if (status) "on" else "off"}")
    }
}

@ServiceScope
class ForegroundNotificationNeedsUpdatingWhenErrorStateChanges @Inject constructor(
    private val service: XmppConnectionService,
    private val hasEnabledAccounts: HasEnabledAccounts
) {
    operator fun invoke(): Boolean {
        return !service.mForceForegroundService.get() && Compatibility.keepForegroundService(service) && hasEnabledAccounts()
    }
}

@ServiceScope
class OnTaskRemoved @Inject constructor(
    private val service: XmppConnectionService,
    private val hasEnabledAccounts: HasEnabledAccounts
) {
    operator fun invoke(rootIntent: Intent) {
//        super.onTaskRemoved(rootIntent)
        if (Compatibility.keepForegroundService(service) && hasEnabledAccounts() || service.mForceForegroundService.get()) {
            Timber.d("ignoring onTaskRemoved because foreground service is activated")
        } else {
            service.logoutAndSave(false)
        }
    }
}

@ServiceScope
class LogoutAndSave @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val disconnect: Disconnect
) {
    operator fun invoke(stop: Boolean) {
        var activeAccounts = 0
        for (account in service.accounts!!) {
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
            service.stopSelf()
        }
    }
}

@ServiceScope
class SchedulePostConnectivityChange @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke() {
        val alarmManager = service.getSystemService(Context.ALARM_SERVICE) as AlarmManager ?: return
        val triggerAtMillis =
            SystemClock.elapsedRealtime() + Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL * 1000
        val intent = Intent(service, EventReceiver::class.java)
        intent.action = XmppConnvectionConstans.ACTION_POST_CONNECTIVITY_CHANGE
        try {
            val pendingIntent = PendingIntent.getBroadcast(service, 1, intent, 0)
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
            Timber.e(e, "unable to schedule alarm for post connectivity change")
        }

    }
}

@ServiceScope
class ScheduleWakeUpCall @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(seconds: Int, requestCode: Int) {
        val timeToWake =
            SystemClock.elapsedRealtime() + (if (seconds < 0) 1 else seconds + 1) * 1000
        val alarmManager = service.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(service, EventReceiver::class.java)
        intent.action = "ping"
        try {
            val pendingIntent = PendingIntent.getBroadcast(service, requestCode, intent, 0)
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent)
        } catch (e: RuntimeException) {
            Timber.e(e, "unable to schedule alarm for ping")
        }

    }

}

@ServiceScope
class SheduleNextIdlePing @Inject constructor(
    private val service: XmppConnectionService
) {

    @TargetApi(Build.VERSION_CODES.M)
    operator fun invoke() {
        val timeToWake = SystemClock.elapsedRealtime() + Config.IDLE_PING_INTERVAL * 1000
        val alarmManager = service.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(service, EventReceiver::class.java)
        intent.action = XmppConnectionService.ACTION_IDLE_PING
        try {
            val pendingIntent = PendingIntent.getBroadcast(service, 0, intent, 0)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                timeToWake,
                pendingIntent
            )
        } catch (e: RuntimeException) {
            Timber.d(e, "unable to schedule alarm for idle ping")
        }
    }
}

@ServiceScope
class CreateConnection @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(account: Account): XmppConnection {
        val connection = XmppConnection(account, service)
        connection.setOnMessagePacketReceivedListener(service.mMessageParser)
        connection.setOnStatusChangedListener(service.statusListener)
        connection.setOnPresencePacketReceivedListener(service.mPresenceParser)
        connection.setOnUnregisteredIqPacketReceivedListener(service.iqParser)
        connection.setOnJinglePacketReceivedListener(service.jingleListener)
        connection.setOnBindListener(service.onBindListener)
        connection.setOnMessageAcknowledgeListener(service.onMessageAcknowledgedListener)
        connection.addOnAdvancedStreamFeaturesAvailableListener(service.messageArchiveService)
        connection.addOnAdvancedStreamFeaturesAvailableListener(service.avatarService)
        val axolotlService = account.axolotlService
        if (axolotlService != null) {
            connection.addOnAdvancedStreamFeaturesAvailableListener(axolotlService)
        }
        return connection
    }
}

@ServiceScope
class SendChatState @Inject constructor(
    private val sendChatStates: SendChatStates,
    private val messageGenerator: MessageGenerator,
    private val sendMessagePacket: SendMessagePacket
) {
    operator fun invoke(conversation: Conversation) {
        if (sendChatStates()) {
            val packet = messageGenerator.generateChatState(conversation)
            sendMessagePacket(conversation.account, packet)
        }
    }
}

@ServiceScope
class SendFileMessage @Inject constructor(
    private val fileBackend: FileBackend,
    private val httpConnectionManager: HttpConnectionManager,
    private val jingleConnectionManager: JingleConnectionManager
) {
    operator fun invoke(message: Message, delay: Boolean) {
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
}

@ServiceScope
class SendMessage @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val notificationService: NotificationService,
    private val createContact: CreateContact,
    private val fileBackend: FileBackend,
    private val messageGenerator: MessageGenerator,
    private val updateConversationUi: UpdateConversationUi,
    private val markMessage: MarkMessage,
    private val sendMessagePacket: SendMessagePacket
) {
    operator fun invoke(message: Message) {
        invoke(message, false, false)
    }

    operator fun invoke(message: Message, resend: Boolean, delay: Boolean) {
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
                Timber.d("${account.jid.asBareJid()}: adding ${contact.jid} on sending message")
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
                        service.sendFileMessage(message, delay)
                    }
                } else {
                    packet = messageGenerator.generateChat(message)
                }
                Message.ENCRYPTION_PGP, Message.ENCRYPTION_DECRYPTED -> if (message.needsUploading()) {
                    if (account.httpUploadAvailable(fileBackend.getFile(message, false).size)
                        || conversation.mode == Conversation.MODE_MULTI
                        || message.fixCounterpart()
                    ) {
                        service.sendFileMessage(message, delay)
                    }
                } else {
                    packet = messageGenerator.generatePgpChat(message)
                }
                Message.ENCRYPTION_AXOLOTL -> {
                    message.fingerprint = account.axolotlService.ownFingerprint
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(
                                fileBackend.getFile(
                                    message,
                                    false
                                ).size
                            )
                            || conversation.mode == Conversation.MODE_MULTI
                            || message.fixCounterpart()
                        ) {
                            service.sendFileMessage(message, delay)
                        }
                    } else {
                        val axolotlMessage =
                            account.axolotlService.fetchAxolotlMessageFromCache(message)
                        if (axolotlMessage == null) {
                            account.axolotlService.preparePayloadMessage(message, delay)
                        } else {
                            packet =
                                messageGenerator.generateAxolotlChat(message, axolotlMessage)
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
                            Timber.e("error updated message in DB after edit")
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
                    Timber.e("error updated message in DB after edit")
                }
            }
            updateConversationUi()
        }
        if (packet != null) {
            if (delay) {
                messageGenerator.addDelay(packet, message.timeSent)
            }
            if (conversation.setOutgoingChatState(Config.DEFAULT_CHATSTATE)) {
                if (service.sendChatStates()) {
                    packet.addChild(ChatState.toElement(conversation.outgoingChatState))
                }
            }
            sendMessagePacket(account, packet)
        }
    }
}


@ServiceScope
class SendUnsentMessages @Inject constructor(
    private val resendMessage: ResendMessage
) {
    operator fun invoke(conversation: Conversation) {
        conversation.findWaitingMessages { message -> resendMessage(message, true) }
    }
}

@ServiceScope
class ResendMessage @Inject constructor(
    private val sendMessage: SendMessage
) {
    operator fun invoke(message: Message, delay: Boolean) {
        sendMessage(message, true, delay)
    }
}


@ServiceScope
class GetLongPreference @Inject constructor(
    private val preferences: SharedPreferences,
    private val resources: Resources
) {
    operator fun invoke(name: String, @IntegerRes res: Int): Long {
        val defaultValue = resources.getInteger(res).toLong()
        try {
            return java.lang.Long.parseLong(
                preferences.getString(
                    name,
                    defaultValue.toString()
                )!!
            )
        } catch (e: NumberFormatException) {
            return defaultValue
        }

    }
}

@ServiceScope
class GetBooleanPreference @Inject constructor(
    private val preferences: SharedPreferences,
    private val resources: Resources
) {
    operator fun invoke(name: String, @BoolRes res: Int): Boolean {
        return preferences.getBoolean(name, resources.getBoolean(res))
    }
}

@ServiceScope
class ConfirmMessages @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference("confirm_messages", R.bool.confirm_messages)
    }
}

@ServiceScope
class AllowMessageCorrection @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference("allow_message_correction", R.bool.allow_message_correction)
    }
}

@ServiceScope
class SendChatStates @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference("chat_states", R.bool.chat_states)
    }
}

@ServiceScope
class SynchronizeWithBookmarks @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference("autojoin", R.bool.autojoin)
    }
}

@ServiceScope
class IndicateReceived @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference("indicate_received", R.bool.indicate_received)
    }
}

@ServiceScope
class UseTorToConnect @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return QuickConversationsService.isConversations() && getBooleanPreference(
            "use_tor",
            R.bool.use_tor
        )
    }
}

@ServiceScope
class ShowExtendedConnectionOptions @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return QuickConversationsService.isConversations() && getBooleanPreference(
            "show_connection_options",
            R.bool.show_connection_options
        )
    }
}

@ServiceScope
class BroadcastLastActivity @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference(
            SettingsActivity.BROADCAST_LAST_ACTIVITY,
            R.bool.last_activity
        )
    }
}

@ServiceScope
class UnreadCount @Inject constructor(
    private val getConversations: GetConversations
) {
    operator fun invoke(): Int {
        var count = 0
        for (conversation in getConversations()) {
            count += conversation.unreadCount()
        }
        return count
    }

}


@ServiceScope
class ThreadSafeList @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun <T> invoke(set: Set<T>): List<T> {
        synchronized(service.LISTENER_LOCK) {
            return if (set.size == 0) Collections.emptyList() else ArrayList(set)
        }
    }
}

@ServiceScope
class ShowErrorToastInUi @Inject constructor(
    private val service: XmppConnectionService,
    private val threadSafeList: ThreadSafeList
) {
    operator fun invoke(resId: Int) {
        for (listener in threadSafeList(service.mOnShowErrorToasts)) {
            listener.onShowErrorToast(resId)
        }
    }
}

@ServiceScope
class UpdateConversationUi @Inject constructor(
    private val service: XmppConnectionService,
    private val threadSafeList: ThreadSafeList
) {
    operator fun invoke() {
        for (listener in threadSafeList(service.mOnConversationUpdates)) {
            listener.onConversationUpdate()
        }
    }
}

@ServiceScope
class UpdateAccountUi @Inject constructor(
    private val service: XmppConnectionService,
    private val threadSafeList: ThreadSafeList
) {
    operator fun invoke() {
        for (listener in threadSafeList(service.mOnAccountUpdates)) {
            listener.onAccountUpdate()
        }
    }
}

@ServiceScope
class UpdateRosterUi @Inject constructor(
    private val service: XmppConnectionService,
    private val threadSafeList: ThreadSafeList
) {
    operator fun invoke() {
        for (listener in threadSafeList(service.mOnRosterUpdates)) {
            listener.onRosterUpdate()
        }
    }
}

@ServiceScope
class DisplayCaptchaRequest @Inject constructor(
    private val service: XmppConnectionService,
    private val threadSafeList: ThreadSafeList
) {
    operator fun invoke(
        account: Account,
        id: String,
        data: Data,
        captcha: Bitmap
    ): Boolean {
        if (service.mOnCaptchaRequested.size > 0) {
            val metrics = service.applicationContext.resources.displayMetrics
            val scaled = Bitmap.createScaledBitmap(
                captcha, (captcha.width * metrics.scaledDensity).toInt(),
                (captcha.height * metrics.scaledDensity).toInt(), false
            )
            for (listener in threadSafeList(service.mOnCaptchaRequested)) {
                listener.onCaptchaRequested(account, id, data, scaled)
            }
            return true
        }
        return false
    }
}

@ServiceScope
class UpdateBlocklistUi @Inject constructor(
    private val service: XmppConnectionService,
    private val threadSafeList: ThreadSafeList
) {
    operator fun invoke(status: OnUpdateBlocklist.Status) {
        for (listener in threadSafeList(service.mOnUpdateBlocklist)) {
            listener.OnUpdateBlocklist(status)
        }
    }
}

@ServiceScope
class UpdateMucRosterUi @Inject constructor(
    private val service: XmppConnectionService,
    private val threadSafeList: ThreadSafeList
) {
    operator fun invoke() {
        for (listener in threadSafeList(service.mOnMucRosterUpdate)) {
            listener.onMucRosterUpdate()
        }
    }
}

@ServiceScope
class KeyStatusUpdated @Inject constructor(
    private val service: XmppConnectionService,
    private val threadSafeList: ThreadSafeList
) {
    operator fun invoke(report: AxolotlService.FetchStatus?) {
        for (listener in threadSafeList(service.mOnKeyStatusUpdated)) {
            listener.onKeyStatusUpdated(report)
        }
    }
}

@ServiceScope
class FindAccountByJid @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(accountJid: Jid): Account? {
        for (account in service.accounts!!) {
            if (account.jid.asBareJid() == accountJid.asBareJid()) {
                return account
            }
        }
        return null
    }
}

@ServiceScope
class FindAccountByUuid @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(uuid: String): Account? {
        for (account in service.accounts!!) {
            if (account.uuid == uuid) {
                return account
            }
        }
        return null
    }
}

@ServiceScope
class FindConversationByUuid @Inject constructor(
    private val service: XmppConnectionService,
    private val getConversations: GetConversations
) {
    operator fun invoke(uuid: String): Conversation? {
        for (conversation in getConversations()) {
            if (conversation.uuid == uuid) {
                return conversation
            }
        }
        return null
    }
}

@ServiceScope
class FindUniqueConversationByJid @Inject constructor(
    private val service: XmppConnectionService,
    private val getConversations: GetConversations
) {
    operator fun invoke(xmppUri: XmppUri): Conversation? {
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
}

@ServiceScope
class MarkRead @Inject constructor(
    private val service: XmppConnectionService,
    private val notificationService: NotificationService,
    private val databaseBackend: DatabaseBackend,
    private val updateUnreadCountBadge: UpdateUnreadCountBadge

) {
    operator fun invoke(conversation: Conversation, dismiss: Boolean): Boolean {
        return invoke(conversation, null, dismiss).size > 0
    }

    operator fun invoke(conversation: Conversation) {
        invoke(conversation, null, true)
    }

    operator fun invoke(
        conversation: Conversation,
        upToUuid: String?,
        dismiss: Boolean
    ): List<Message> {
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
            service.mDatabaseWriterExecutor.execute(runnable)
            updateUnreadCountBadge()
            return readMessages
        } else {
            return readMessages
        }
    }

}

@ServiceScope
class UpdateUnreadCountBadge @Inject constructor(
    private val service: XmppConnectionService,
    private val unreadCount: UnreadCount
) {
    @Synchronized
    operator fun invoke() {
        val count = unreadCount()
        if (service.unreadCount != count) {
            Timber.d("update unread count to $count")
            if (count > 0) {
                ShortcutBadger.applyCount(service.applicationContext, count)
            } else {
                ShortcutBadger.removeCount(service.applicationContext)
            }
            service.unreadCount = count
        }
    }
}


@ServiceScope
class FindOrCreateConversation @Inject constructor(
    private val service: XmppConnectionService,
    private val find: Find,
    private val databaseBackend: DatabaseBackend,
    private val updateConversationUi: UpdateConversationUi,
    private val messageArchiveService: MessageArchiveService,
    private val joinMuc: JoinMuc
) {
    operator fun invoke(
        account: Account,
        jid: Jid,
        muc: Boolean,
        async: Boolean
    ): Conversation {
        return service.findOrCreateConversation(account, jid, muc, false, async)
    }

    operator fun invoke(
        account: Account,
        jid: Jid?,
        muc: Boolean,
        joinAfterCreate: Boolean,
        async: Boolean
    ): Conversation {
        return service.findOrCreateConversation(account, jid, muc, joinAfterCreate, null, async)
    }

    operator fun invoke(
        account: Account,
        jid: Jid?,
        muc: Boolean,
        joinAfterCreate: Boolean,
        query: MessageArchiveService.Query?,
        async: Boolean
    ): Conversation {
        synchronized(service.conversations) {
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
                service.databaseBackend.createConversation(conversation)
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
                service.mDatabaseReaderExecutor.execute(runnable)
            } else {
                runnable.run()
            }
            service.conversations.add(conversation)
            updateConversationUi()
            return conversation
        }
    }
}


@ServiceScope
class ArchiveConversation @Inject constructor(
    private val service: XmppConnectionService,
    private val notificationService: NotificationService,
    private val messageArchiveService: MessageArchiveService,
    private val synchronizeWithBookmarks: SynchronizeWithBookmarks,
    private val pushBookmarks: PushBookmarks,
    private val leaveMuc: LeaveMuc,
    private val stopPresenceUpdatesTo: StopPresenceUpdatesTo,
    private val updateConversation: UpdateConversation,
    private val updateConversationUi: UpdateConversationUi
) {
    operator fun invoke(conversation: Conversation) {
        invoke(conversation, true)
    }

    operator fun invoke(
        conversation: Conversation,
        maySyncronizeWithBookmarks: Boolean
    ) {
        notificationService.clear(conversation)
        conversation.status = Conversation.STATUS_ARCHIVED
        conversation.nextMessage = null
        synchronized(service.conversations) {
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
            service.conversations.remove(conversation)
            updateConversationUi()
        }
    }
}

@ServiceScope
class StopPresenceUpdatesTo @Inject constructor(
    private val sendPresencePacket: SendPresencePacket,
    private val presenceGenerator: PresenceGenerator
) {
    operator fun invoke(contact: Contact) {
        Timber.d("Canceling presence request from " + contact.jid.toString())
        sendPresencePacket(contact.account, presenceGenerator.stopPresenceUpdatesTo(contact))
        contact.resetOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
    }
}

@ServiceScope
class CreateAccount @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val updateAccountUi: UpdateAccountUi,
    private val syncEnabledAccountSetting: SyncEnabledAccountSetting,
    private val toggleForegroundService: ToggleForegroundService
) {
    operator fun invoke(account: Account) {
        account.initAccountServices(service)
        databaseBackend.createAccount(account)
        service.accounts!!.add(account)
        service.reconnectAccountInBackground(account)
        updateAccountUi()
        syncEnabledAccountSetting()
        toggleForegroundService()
    }
}

@ServiceScope
class SyncEnabledAccountSetting @Inject constructor(
    private val service: XmppConnectionService,
    private val preferences: SharedPreferences,
    private val hasEnabledAccounts: HasEnabledAccounts,
    private val toggleSetProfilePictureActivity: ToggleSetProfilePictureActivity
) {
    operator fun invoke() {
        val hasEnabledAccounts = hasEnabledAccounts()
        preferences.edit()
            .putBoolean(EventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts)
            .apply()
        toggleSetProfilePictureActivity(hasEnabledAccounts)
    }
}

@ServiceScope
class ToggleSetProfilePictureActivity @Inject constructor(
    private val service: XmppConnectionService,
    private val packageManager: PackageManager
) {
    operator fun invoke(enabled: Boolean) {
        try {
            val name = ComponentName(service, ChooseAccountForProfilePictureActivity::class.java)
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
}

@ServiceScope
class CreateAccountFromKey @Inject constructor(
    private val service: XmppConnectionService,
    private val findAccountByJid: FindAccountByJid,
    private val createAccount: CreateAccount
) {
    operator fun invoke(alias: String, callback: XmppConnectionService.OnAccountCreated) {
        Thread {
            try {
                val chain = KeyChain.getCertificateChain(service, alias)
                val cert = if (chain != null && chain.isNotEmpty()) chain[0] else null
                if (cert == null) {
                    callback.informUser(R.string.unable_to_parse_certificate)
                    Thread {
                        try {
                            val chain: Array<X509Certificate>? =
                                KeyChain.getCertificateChain(service, alias);
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
                                        service.memorizingTrustManager!!
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
                                KeyChain.getCertificateChain(service, alias);
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
                                        service.memorizingTrustManager!!
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
                            service.memorizingTrustManager!!.getNonInteractive(account.jid.domain)
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
}

@ServiceScope
class UpdateKeyInAccount @Inject constructor(
    private val service: XmppConnectionService,
    private val showErrorToastInUi: ShowErrorToastInUi,
    private val databaseBackend: DatabaseBackend
) {
    operator fun invoke(account: Account, alias: String) {
        Timber.d(account.jid.asBareJid().toString() + ": update key in account " + alias)
        try {
            val chain = KeyChain.getCertificateChain(service, alias)
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
                        service.memorizingTrustManager!!.nonInteractive.checkClientTrusted(chain, "RSA")
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
}

@ServiceScope
class UpdateAccount @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val reconnectAccountInBackground: ReconnectAccountInBackground,
    private val updateAccountUi: UpdateAccountUi,
    private val notificationService: NotificationService,
    private val toggleForegroundService: ToggleForegroundService,
    private val syncEnabledAccountSetting: SyncEnabledAccountSetting
) {
    operator fun invoke(account: Account): Boolean {
        if (databaseBackend.updateAccount(account)) {
            account.setShowErrorNotification(true)
            service.statusListener.onStatusChanged(account)
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
}

@ServiceScope
class UpdateAccountPasswordOnServer @Inject constructor(
    private val service: XmppConnectionService,
    private val iqGenerator: IqGenerator,
    private val sendIqPacket: SendIqPacket,
    private val databaseBackend: DatabaseBackend
) {
    operator fun invoke(
        account: Account,
        newPassword: String,
        callback: XmppConnectionService.OnAccountPasswordChanged
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
}

@ServiceScope
class DeleteAccount @Inject constructor(
    private val service: XmppConnectionService,
    private val leaveMuc: LeaveMuc,
    private val disconnect: Disconnect,
    private val databaseBackend: DatabaseBackend,
    private val updateAccountUi: UpdateAccountUi,
    private val notificationService: NotificationService,
    private val syncEnabledAccountSetting: SyncEnabledAccountSetting,
    private val toggleForegroundService: ToggleForegroundService
) {
    operator fun invoke(account: Account) {
        synchronized(service.conversations) {
            val conversations = service.conversations
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
                    Timber.d("%s: unable to delete account", account.jid.asBareJid())
                }
            }
            service.mDatabaseWriterExecutor.execute(runnable)
            service.accounts!!.remove(account)
            service.mRosterSyncTaskManager.clear(account)
            updateAccountUi()
            notificationService.updateErrorNotification()
            syncEnabledAccountSetting()
            toggleForegroundService()
        }
    }
}

@ServiceScope
class SetOnConversationListChangedListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToForeground: SwitchToForeground
) {
    operator fun invoke(listener: XmppConnectionService.OnConversationUpdate) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!service.mOnConversationUpdates.add(listener)) {
                Timber.w("%s is already registered as ConversationListChangedListener", listener.javaClass.name)
            }
            service.notificationService.setIsInForeground(service.mOnConversationUpdates.size > 0)
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }
}

@ServiceScope
class RemoveOnConversationListChangedListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToBackground: SwitchToBackground
) {
    operator fun invoke(listener: XmppConnectionService.OnConversationUpdate) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            service.mOnConversationUpdates.remove(listener)
            service.notificationService.setIsInForeground(service.mOnConversationUpdates.size > 0)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }
}

@ServiceScope
class SetOnShowErrorToastListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToForeground: SwitchToForeground
) {
    operator fun invoke(listener: XmppConnectionService.OnShowErrorToast) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!service.mOnShowErrorToasts.add(listener)) {
                Timber.w("%s is already registered as OnShowErrorToastListener", listener.javaClass.name)
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }
}

@ServiceScope
class RemoveOnShowErrorToastListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToBackground: SwitchToBackground
) {
    operator fun invoke(onShowErrorToast: XmppConnectionService.OnShowErrorToast) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            service.mOnShowErrorToasts.remove(onShowErrorToast)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }
}

@ServiceScope
class SetOnAccountListChangedListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToForeground: SwitchToForeground
) {
    operator fun invoke(listener: XmppConnectionService.OnAccountUpdate) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!service.mOnAccountUpdates.add(listener)) {
                Timber.w("%s is already registered as OnAccountListChangedtListener", listener.javaClass.name)
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }
}

@ServiceScope
class RemoveOnAccountListChangedListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToBackground: SwitchToBackground
) {
    operator fun invoke(listener: XmppConnectionService.OnAccountUpdate) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            service.mOnAccountUpdates.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }
}

@ServiceScope
class SetOnCaptchaRequestedListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToForeground: SwitchToForeground
) {
    operator fun invoke(listener: XmppConnectionService.OnCaptchaRequested) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!service.mOnCaptchaRequested.add(listener)) {
                Timber.w("%s is already registered as OnCaptchaRequestListener", listener.javaClass.name)
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }
}

@ServiceScope
class RemoveOnCaptchaRequestedListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToBackground: SwitchToBackground
) {
    operator fun invoke(listener: XmppConnectionService.OnCaptchaRequested) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            service.mOnCaptchaRequested.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }
}

@ServiceScope
class SetOnRosterUpdateListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToForeground: SwitchToForeground
) {
    operator fun invoke(listener: XmppConnectionService.OnRosterUpdate) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!service.mOnRosterUpdates.add(listener)) {
                Timber.w("%s is already registered as OnRosterUpdateListener", listener.javaClass.name)
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }
}

@ServiceScope
class RemoveOnRosterUpdateListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToBackground: SwitchToBackground
) {
    operator fun invoke(listener: XmppConnectionService.OnRosterUpdate) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            service.mOnRosterUpdates.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }
}

@ServiceScope
class SetOnUpdateBlocklistListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToForeground: SwitchToForeground
) {
    operator fun invoke(listener: OnUpdateBlocklist) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!service.mOnUpdateBlocklist.add(listener)) {
                Timber.w("%s is already registered as OnUpdateBlocklistListener", listener.javaClass.name)
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }
}

@ServiceScope
class RemoveOnUpdateBlocklistListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToBackground: SwitchToBackground
) {
    operator fun invoke(listener: OnUpdateBlocklist) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            service.mOnUpdateBlocklist.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }
}

@ServiceScope
class SetOnKeyStatusUpdatedListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToForeground: SwitchToForeground
) {
    operator fun invoke(listener: OnKeyStatusUpdated) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!service.mOnKeyStatusUpdated.add(listener)) {
                Timber.w("%s is already registered as OnKeyStatusUpdateListener", listener.javaClass.name)
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }
}

@ServiceScope
class RemoveOnNewKeysAvailableListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToBackground: SwitchToBackground
) {
    operator fun invoke(listener: OnKeyStatusUpdated) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            service.mOnKeyStatusUpdated.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }
}

@ServiceScope
class SetOnMucRosterUpdateListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToForeground: SwitchToForeground
) {
    operator fun invoke(listener: XmppConnectionService.OnMucRosterUpdate) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            remainingListeners = checkListeners()
            if (!service.mOnMucRosterUpdate.add(listener)) {
                Timber.w("%s is already registered as OnMucRosterListener", listener.javaClass.name)
            }
        }
        if (remainingListeners) {
            switchToForeground()
        }
    }
}

@ServiceScope
class RemoveOnMucRosterUpdateListener @Inject constructor(
    private val service: XmppConnectionService,
    private val checkListeners: CheckListeners,
    private val switchToBackground: SwitchToBackground
) {
    operator fun invoke(listener: XmppConnectionService.OnMucRosterUpdate) {
        val remainingListeners: Boolean
        synchronized(service.LISTENER_LOCK) {
            service.mOnMucRosterUpdate.remove(listener)
            remainingListeners = checkListeners()
        }
        if (remainingListeners) {
            switchToBackground()
        }
    }
}

@ServiceScope
class CheckListeners @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): Boolean {
        return (service.mOnAccountUpdates.size == 0
                && service.mOnConversationUpdates.size == 0
                && service.mOnRosterUpdates.size == 0
                && service.mOnCaptchaRequested.size == 0
                && service.mOnMucRosterUpdate.size == 0
                && service.mOnUpdateBlocklist.size == 0
                && service.mOnShowErrorToasts.size == 0
                && service.mOnKeyStatusUpdated.size == 0)
    }
}

@ServiceScope
class SwitchToForeground @Inject constructor(
    private val service: XmppConnectionService,
    private val broadcastLastActivity: BroadcastLastActivity,
    private val getConversations: GetConversations,
    private val sendPresence: SendPresence
) {
    operator fun invoke() {
        val broadcastLastActivity = broadcastLastActivity()
        for (conversation in getConversations()) {
            if (conversation.mode == Conversation.MODE_MULTI) {
                conversation.mucOptions.resetChatState()
            } else {
                conversation.incomingChatState = Config.DEFAULT_CHATSTATE
            }
        }
        for (account in service.accounts!!) {
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
}

@ServiceScope
class SwitchToBackground @Inject constructor(
    private val service: XmppConnectionService,
    private val broadcastLastActivity: BroadcastLastActivity,
    private val preferences: SharedPreferences,
    private val sendPresence: SendPresence
) {
    operator fun invoke() {
        val broadcastLastActivity = broadcastLastActivity()
        if (broadcastLastActivity) {
            service.mLastActivity = System.currentTimeMillis()
            val editor = preferences.edit()
            editor.putLong(XmppConnvectionConstans.SETTING_LAST_ACTIVITY_TS, service.mLastActivity)
            editor.apply()
        }
        for (account in service.accounts!!) {
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
        service.notificationService.setIsInForeground(false)
        Timber.d("app switched into background")
    }
}

@ServiceScope
class ConnectMultiModeConversations @Inject constructor(
    private val getConversations: GetConversations,
    private val joinMuc: JoinMuc
) {
    operator fun invoke(account: Account) {
        val conversations = getConversations()
        for (conversation in conversations) {
            if (conversation.mode == Conversation.MODE_MULTI && conversation.account === account) {
                joinMuc(conversation)
            }
        }
    }
}

@ServiceScope
class JoinMuc @Inject constructor(
    private val databaseBackend: DatabaseBackend,
    private val sendPresencePacket: SendPresencePacket,
    private val presenceGenerator: PresenceGenerator,
    private val fetchConferenceConfiguration: FetchConferenceConfiguration,
    private val updateConversationUi: UpdateConversationUi,
    private val messageArchiveService: MessageArchiveService,
    private val fetchConferenceMembers: FetchConferenceMembers,
    private val saveConversationAsBookmark: SaveConversationAsBookmark,
    private val sendUnsentMessages: SendUnsentMessages
) {
    operator fun invoke(conversation: Conversation) {
        invoke(conversation, null, false)
    }

    operator fun invoke(conversation: Conversation, followedInvite: Boolean) {
        invoke(conversation, null, followedInvite)
    }

    operator fun invoke(
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
            fetchConferenceConfiguration(conversation, object :
                XmppConnectionService.OnConferenceConfigurationFetched {

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
                    Timber.d(account.jid.asBareJid().toString() + ": joining conversation " + joinJid.toString())
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
                        Timber.d(account.jid.asBareJid().toString() + ": conversation (" + conversation.jid + ") got archived before IQ result")
                        return
                    }
                    join(conversation)
                }

                override fun onFetchFailed(conversation: Conversation, error: Element?) {
                    if (conversation.status == Conversation.STATUS_ARCHIVED) {
                        Timber.d(account.jid.asBareJid().toString() + ": conversation (" + conversation.jid + ") got archived before IQ result")
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
}

@ServiceScope
class FetchConferenceMembers @Inject constructor(
    private val sendIqPacket: SendIqPacket,
    private val iqGenerator: IqGenerator,
    private val updateConversation: UpdateConversation,
    private val updateConversationUi: UpdateConversationUi,
    private val avatarService: AvatarService,
    private val updateMucRosterUi: UpdateMucRosterUi
) {
    operator fun invoke(conversation: Conversation) {
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
                    Timber.d(account.jid.asBareJid().toString() + ": could not request affiliation " + affiliations[i] + " in " + conversation.jid.asBareJid())
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
                                Timber.d(account.jid.asBareJid().toString() + ": removed " + jid + " from crypto targets of " + conversation.name)
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
            sendIqPacket(
                account,
                iqGenerator.queryAffiliation(conversation, affiliation),
                callback
            )
        }
        Timber.d("%s%s", account.jid.asBareJid().toString() + ": fetching members for ", conversation.name)
    }
}


@ServiceScope
class ProvidePasswordForMuc @Inject constructor(
    private val service: XmppConnectionService,
    private val synchronizeWithBookmarks: SynchronizeWithBookmarks,
    private val pushBookmarks: PushBookmarks,
    private val updateConversation: UpdateConversation,
    private val joinMuc: JoinMuc
) {
    operator fun invoke(conversation: Conversation, password: String) {
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
}

@ServiceScope
class HasEnabledAccounts @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(): Boolean {
        if (service.accounts == null) {
            return false
        }
        for (account in service.accounts!!) {
            if (account.isEnabled) {
                return true
            }
        }
        return false
    }

}

@ServiceScope
class GetAttachments @Inject constructor(
    private val fileBackend: FileBackend,
    private val databaseBackend: DatabaseBackend
) {
    operator fun invoke(conversation: Conversation, limit: Int, onMediaLoaded: OnMediaLoaded) {
        invoke(conversation.account, conversation.jid.asBareJid(), limit, onMediaLoaded)
    }

    operator fun invoke(account: Account, jid: Jid, limit: Int, onMediaLoaded: OnMediaLoaded) {
        invoke(account.uuid, jid.asBareJid(), limit, onMediaLoaded)
    }

    operator fun invoke(account: String, jid: Jid, limit: Int, onMediaLoaded: OnMediaLoaded) {
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
}

@ServiceScope
class PersistSelfNick @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend,
    private val pushBookmarks: PushBookmarks
) {
    operator fun invoke(self: MucOptions.User) {
        val conversation = self.conversation
        val tookProposedNickFromBookmark =
            conversation.mucOptions.isTookProposedNickFromBookmark
        val full = self.fullJid
        if (full != conversation.jid) {
            Timber.d("nick changed. updating")
            conversation.setContactJid(full)
            databaseBackend.updateConversation(conversation)
        }

        val bookmark = conversation.bookmark
        val bookmarkedNick = bookmark?.nick
        if (bookmark != null && (tookProposedNickFromBookmark || TextUtils.isEmpty(
                bookmarkedNick
            )) && full.resource != bookmarkedNick
        ) {
            Log.d(
                Config.LOGTAG,
                conversation.account.jid.asBareJid().toString() + ": persist nick '" + full.resource + "' into bookmark for " + conversation.jid.asBareJid()
            )
            bookmark.nick = full.resource
            pushBookmarks(bookmark.account)
        }
    }
}

@ServiceScope
class RenameInMuc @Inject constructor(
    private val sendPresencePacket: SendPresencePacket,
    private val databaseBackend: DatabaseBackend,
    private val pushBookmarks: PushBookmarks,
    private val joinMuc: JoinMuc
) {
    operator fun invoke(
        conversation: Conversation,
        nick: String,
        callback: UiCallback<Conversation>
    ): Boolean {
        val options = conversation.mucOptions
        val joinJid = options.createJoinJid(nick) ?: return false
        if (options.online()) {
            val account = conversation.account
            options.setOnRenameListener(object : MucOptions.OnRenameListener {

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
}

@ServiceScope
class LeaveMuc @Inject constructor(
    private val sendPresencePacket: SendPresencePacket,
    private val presenceGenerator: PresenceGenerator
) {
    operator fun invoke(conversation: Conversation) {
        invoke(conversation, false)
    }

    operator fun invoke(conversation: Conversation, now: Boolean) {
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
            Timber.d(conversation.account.jid.asBareJid().toString() + ": leaving muc " + conversation.jid)
        } else {
            account.pendingConferenceLeaves.add(conversation)
        }
    }
}

@ServiceScope
class FindConferenceServer @Inject constructor(
    private val service: XmppConnectionService
) {

    operator fun invoke(account: Account): String? {
        var server: String?
        if (account.xmppConnection != null) {
            server = account.xmppConnection.mucServer
            if (server != null) {
                return server
            }
        }
        for (other in service.accounts!!) {
            if (other !== account && other.xmppConnection != null) {
                server = other.xmppConnection.mucServer
                if (server != null) {
                    return server
                }
            }
        }
        return null
    }
}

@ServiceScope
class CreatePublicChannel @Inject constructor(
    private val service: XmppConnectionService,
    private val joinMuc: JoinMuc,
    private val findOrCreateConversation: FindOrCreateConversation,
    private val pushConferenceConfiguration: PushConferenceConfiguration,
    private val saveConversationAsBookmark: SaveConversationAsBookmark
) {
    operator fun invoke(
        account: Account,
        name: String,
        address: Jid,
        callback: UiCallback<Conversation>
    ) {
        joinMuc(
            conversation = findOrCreateConversation(
                account,
                address,
                true,
                joinAfterCreate = false,
                async = true
            ),
            onConferenceJoined = { conversation ->
                val configuration = IqGenerator.defaultChannelConfiguration()
                if (!TextUtils.isEmpty(name)) {
                    configuration.putString("muc#roomconfig_roomname", name)
                }
                pushConferenceConfiguration(
                    conversation,
                    configuration,
                    object : XmppConnectionService.OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            saveConversationAsBookmark(conversation, name)
                            callback.success(conversation)
                        }

                        override fun onPushFailed() {
                            if (conversation.mucOptions.self.affiliation.ranks(
                                    MucOptions.Affiliation.OWNER
                                )
                            ) {
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
}

@ServiceScope
class CreateAdhocConference @Inject constructor(
    private val service: XmppConnectionService,
    private val findConferenceServer: FindConferenceServer,
    private val findOrCreateConversation: FindOrCreateConversation,
    private val joinMuc: JoinMuc,
    private val pushConferenceConfiguration: PushConferenceConfiguration,
    private val directInvite: DirectInvite,
    private val saveConversationAsBookmark: SaveConversationAsBookmark,
    private val archiveConversation: ArchiveConversation,
    private val invite: Invite
) {
    operator fun invoke(
        account: Account,
        name: String?,
        jids: Iterable<Jid>,
        callback: UiCallback<Conversation>?
    ): Boolean {
        Timber.d(account.jid.asBareJid().toString() + ": creating adhoc conference with " + jids.toString())
        if (account.status == Account.State.ONLINE) {
            try {
                val server = findConferenceServer(account)
                if (server == null) {
                    callback?.error(R.string.no_conference_server_found, null)
                    return false
                }
                val jid = Jid.of(CryptoHelper.pronounceable(service.rng!!), server, null)
                val conversation = findOrCreateConversation(account, jid, true, false, true)
                joinMuc(conversation, object : XmppConnectionService.OnConferenceJoined {
                    override fun invoke(conversation: Conversation) {
                        val configuration = IqGenerator.defaultGroupChatConfiguration()
                        if (!TextUtils.isEmpty(name)) {
                            configuration.putString("muc#roomconfig_roomname", name)
                        }
                        pushConferenceConfiguration(
                            conversation,
                            configuration,
                            object : XmppConnectionService.OnConfigurationPushed {
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

}

@ServiceScope
class FetchConferenceConfiguration @Inject constructor(
    private val sendIqPacket: SendIqPacket,
    private val updateConversation: UpdateConversation,
    private val pushBookmarks: PushBookmarks,
    private val updateConversationUi: UpdateConversationUi
) {
    @JvmOverloads
    operator fun invoke(
        conversation: Conversation,
        callback: XmppConnectionService.OnConferenceConfigurationFetched? = null
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
                    Timber.d(account.jid.asBareJid().toString() + ": muc configuration changed for " + conversation.jid.asBareJid())
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
}

@ServiceScope
class PushNodeConfiguration @Inject constructor(
    private val sendIqPacket: SendIqPacket,
    private val iqGenerator: IqGenerator
) {
    operator fun invoke(
        account: Account,
        node: String,
        options: Bundle?,
        callback: XmppConnectionService.OnConfigurationPushed
    ) {
        invoke(account, account.jid.asBareJid(), node, options, callback)
    }

    operator fun invoke(
        account: Account,
        jid: Jid,
        node: String,
        options: Bundle?,
        callback: XmppConnectionService.OnConfigurationPushed?
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
                                    Timber.d(account.jid.asBareJid().toString() + ": successfully changed node configuration for node " + node)
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
}

@ServiceScope
class PushConferenceConfiguration @Inject constructor(
    private val updateConversation: UpdateConversation,
    private val sendIqPacket: SendIqPacket
) {
    operator fun invoke(
        conversation: Conversation,
        options: Bundle,
        callback: XmppConnectionService.OnConfigurationPushed?
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
}

@ServiceScope
class PushSubjectToConference @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(conference: Conversation, subject: String) {
        val packet =
            service.messageGenerator.conferenceSubject(
                conference,
                StringUtils.nullOnEmpty(subject)
            )
        service.sendMessagePacket(conference.account, packet)
    }
}

@ServiceScope
class ChangeAffiliationInConference @Inject constructor(
    private val service: XmppConnectionService,
    private val sendIqPacket: SendIqPacket,
    private val avatarService: AvatarService
) {
    operator fun invoke(
        conference: Conversation,
        user: Jid,
        affiliation: MucOptions.Affiliation,
        callback: XmppConnectionService.OnAffiliationChanged
    ) {
        val jid = user.asBareJid()
        val request = service.iqGenerator.changeAffiliation(conference, jid, affiliation.toString())
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
}

@ServiceScope
class ChangeAffiliationsInConference @Inject constructor(
    private val service: XmppConnectionService,
    private val sendIqPacket: SendIqPacket
) {
    operator fun invoke(
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
        val request = service.iqGenerator.changeAffiliation(conference, jids, after.toString())
        sendIqPacket(conference.account, request, service.defaultIqHandler)
    }
}

@ServiceScope
class ChangeRoleInConference @Inject constructor(
    private val service: XmppConnectionService,
    private val sendIqPacket: SendIqPacket
) {
    operator fun invoke(conference: Conversation, nick: String, role: MucOptions.Role) {
        val request = service.iqGenerator.changeRole(conference, nick, role.toString())
        Timber.d(request.toString())
        sendIqPacket(conference.account, request, OnIqPacketReceived { account, packet ->
            if (packet.getType() != IqPacket.TYPE.RESULT) {
                Timber.d(account.getJid().asBareJid().toString() + " unable to change role of " + nick)
            }
        })
    }
}

@ServiceScope
class DestroyRoom @Inject constructor(
    private val service: XmppConnectionService,
    private val sendIqPacket: SendIqPacket
) {
    operator fun invoke(conversation: Conversation, callback: XmppConnectionService.OnRoomDestroy?) {
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
}

@ServiceScope
class Disconnect @Inject constructor(
    private val service: XmppConnectionService,
    private val getConversations: GetConversations,
    private val leaveMuc: LeaveMuc,
    private val sendOfflinePresence: SendOfflinePresence
) {
    operator fun invoke(account: Account, force: Boolean) {
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
}

@ServiceScope
class UpdateMessage @Inject constructor(
    private val databaseBackend: DatabaseBackend,
    private val updateConversationUi: UpdateConversationUi
) {
    operator fun invoke(message: Message, includeBody: Boolean = true) {
        databaseBackend.updateMessage(message, includeBody)
        updateConversationUi()
    }

    operator fun invoke(message: Message, uuid: String) {
        if (!databaseBackend.updateMessage(message, uuid)) {
            Timber.e("error updated message in DB after edit")
        }
        updateConversationUi()
    }
}

@ServiceScope
class SyncDirtyContacts @Inject constructor(
    private val pushContactToServer: PushContactToServer,
    private val deleteContactOnServer: DeleteContactOnServer
) {
    operator fun invoke(account: Account) {
        for (contact in account.roster.contacts) {
            if (contact.getOption(Contact.Options.DIRTY_PUSH)) {
                pushContactToServer(contact)
            }
            if (contact.getOption(Contact.Options.DIRTY_DELETE)) {
                deleteContactOnServer(contact)
            }
        }
    }
}

@ServiceScope
class CreateContact @Inject constructor(
    private val service: XmppConnectionService,
    private val pushContactToServer: PushContactToServer
) {
    operator fun invoke(contact: Contact, autoGrant: Boolean) {
        if (autoGrant) {
            contact.setOption(Contact.Options.PREEMPTIVE_GRANT)
            contact.setOption(Contact.Options.ASKING)
        }
        pushContactToServer(contact)
    }
}

@ServiceScope
class PushContactToServer @Inject constructor(
    private val service: XmppConnectionService,
    private val sendPresencePacket: SendPresencePacket,
    private val presenceGenerator: PresenceGenerator,
    private val syncRoster: SyncRoster
) {
    operator fun invoke(contact: Contact) {
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
            account.xmppConnection.sendIqPacket(iq, service.defaultIqHandler)
            if (sendUpdates) {
                sendPresencePacket(account, presenceGenerator.sendPresenceUpdatesTo(contact))
            }
            if (ask) {
                sendPresencePacket(
                    account,
                    presenceGenerator.requestPresenceUpdatesFrom(contact)
                )
            }
        } else {
            syncRoster(contact.account)
        }
    }
}

@ServiceScope
class PublishMucAvatar @Inject constructor(
    private val service: XmppConnectionService,
    private val fileBackend: FileBackend,
    private val publishMucAvatar: PublishMucAvatar,
    private val iqGenerator: IqGenerator,
    private val sendIqPacket: SendIqPacket
) {
    operator fun invoke(
        conversation: Conversation,
        image: Uri,
        callback: OnAvatarPublication
    ) {
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

    operator fun invoke(
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
                        Timber.d("failed to publish vcard " + publicationResponse.getError()!!)
                        callback.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject)
                    }
                })
            } else {
                Timber.d("failed to request vcard " + response.toString())
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_no_server_support)
            }
        })
    }
}

@ServiceScope
class PublishAvatar @Inject constructor(
    private val service: XmppConnectionService,
    private val publishAvatarMetadata: PublishAvatarMetadata,
    private val pushNodeConfiguration: PushNodeConfiguration
) {
    operator fun invoke(account: Account, avatar: Avatar?, callback: OnAvatarPublication?) {
        val options: Bundle?
        if (account.xmppConnection.features.pepPublishOptions()) {
            options = PublishOptions.openAccess()
        } else {
            options = null
        }
        invoke(account, avatar, options, true, callback)
    }

    operator fun invoke(
        account: Account,
        avatar: Avatar?,
        options: Bundle?,
        retry: Boolean,
        callback: OnAvatarPublication?
    ) {
        Timber.d(account.jid.asBareJid().toString() + ": publishing avatar. options=" + options)
        val packet = service.iqGenerator.publishAvatar(avatar, options)
        service.sendIqPacket(account, packet, OnIqPacketReceived { account, result ->
            if (result.type == IqPacket.TYPE.RESULT) {
                publishAvatarMetadata(account, avatar, options, true, callback)
            } else if (retry && PublishOptions.preconditionNotMet(result)) {
                pushNodeConfiguration(
                    account,
                    "urn:xmpp:avatar:data",
                    options,
                    object : XmppConnectionService.OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            Timber.d(account.jid.asBareJid().toString() + ": changed node configuration for avatar node")
                            invoke(account, avatar, options, false, callback)
                        }

                        override fun onPushFailed() {
                            Timber.d(account.jid.asBareJid().toString() + ": unable to change node configuration for avatar node")
                            invoke(account, avatar, null, false, callback)
                        }
                    })
            } else {
                val error = result.findChild("error")
                Timber.d("${account.jid.asBareJid()}: server rejected avatar ${avatar!!.size / 1024}KiB ${error?.toString() ?: ""}")
                callback?.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject)
            }
        })
    }
}

@ServiceScope
class PublishAvatarMetadata @Inject constructor(
    private val service: XmppConnectionService,
    private val sendIqPacket: SendIqPacket,
    private val avatarService: AvatarService,
    private val databaseBackend: DatabaseBackend,
    private val notifyAccountAvatarHasChanged: NotifyAccountAvatarHasChanged,
    private val pushNodeConfiguration: PushNodeConfiguration,
    private val publishAvatarMetadata: PublishAvatarMetadata
) {
    operator fun invoke(
        account: Account,
        avatar: Avatar?,
        options: Bundle?,
        retry: Boolean,
        callback: OnAvatarPublication?
    ) {
        val packet = service.iqGenerator.publishAvatarMetadata(avatar, options)
        sendIqPacket(account, packet, OnIqPacketReceived { account, result ->
            if (result.type == IqPacket.TYPE.RESULT) {
                if (account.setAvatar(avatar!!.filename)) {
                    avatarService.clear(account)
                    databaseBackend.updateAccount(account)
                    notifyAccountAvatarHasChanged(account)
                }
                Timber.d(account.jid.asBareJid().toString() + ": published avatar " + avatar.size / 1024 + "KiB")
                callback?.onAvatarPublicationSucceeded()
            } else if (retry && PublishOptions.preconditionNotMet(result)) {
                pushNodeConfiguration(
                    account,
                    "urn:xmpp:avatar:metadata",
                    options,
                    object : XmppConnectionService.OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            Timber.d(account.jid.asBareJid().toString() + ": changed node configuration for avatar meta data node")
                            publishAvatarMetadata(account, avatar, options, false, callback)
                        }

                        override fun onPushFailed() {
                            Timber.d(account.jid.asBareJid().toString() + ": unable to change node configuration for avatar meta data node")
                            publishAvatarMetadata(account, avatar, null, false, callback)
                        }
                    })
            } else {
                callback?.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject)
            }
        })
    }
}

@ServiceScope
class RepublishAvatarIfNeeded @Inject constructor(
    private val service: XmppConnectionService,
    private val fileBackend: FileBackend,
    private val publishAvatar: PublishAvatar
) {
    operator fun invoke(account: Account) {
        if (account.axolotlService.isPepBroken) {
            Timber.d(account.jid.asBareJid().toString() + ": skipping republication of avatar because pep is broken")
            return
        }
        val packet = service.iqGenerator.retrieveAvatarMetaData(null)
        service.sendIqPacket(account, packet, object : OnIqPacketReceived {

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
                            Timber.d("${account.jid.asBareJid()}: avatar on server was null. republishing")
                            publishAvatar(
                                account,
                                fileBackend.getStoredPepAvatar(account.avatar),
                                null
                            )
                        } else {
                            Timber.e("${account.jid.asBareJid()}: error rereading avatar")
                        }
                    }
                }
            }
        })
    }
}

@ServiceScope
class FetchAvatar @Inject constructor(
    private val service: XmppConnectionService,
    private val fetchAvatarPep: FetchAvatarPep,
    private val fetchAvatarVcard: FetchAvatarVcard
) {
    @JvmOverloads
    operator fun invoke(account: Account, avatar: Avatar, callback: UiCallback<Avatar>? = null) {
        val KEY = XmppConnvectionConstans.generateFetchKey(account, avatar)
        synchronized(service.mInProgressAvatarFetches) {
            if (service.mInProgressAvatarFetches.add(KEY)) {
                when (avatar.origin) {
                    Avatar.Origin.PEP -> {
                        service.mInProgressAvatarFetches.add(KEY)
                        fetchAvatarPep(account, avatar, callback)
                    }
                    Avatar.Origin.VCARD -> {
                        service.mInProgressAvatarFetches.add(KEY)
                        fetchAvatarVcard(account, avatar, callback)
                    }
                }
            } else if (avatar.origin == Avatar.Origin.PEP) {
                service.mOmittedPepAvatarFetches.add(KEY)
            } else {
                Timber.d(account.jid.asBareJid().toString() + ": already fetching " + avatar.origin + " avatar for " + avatar.owner)
            }
        }
    }
}

@ServiceScope
class FetchAvatarPep @Inject constructor(
    private val service: XmppConnectionService,
    private val sendIqPacket: SendIqPacket,
    private val iqParser: IqParser,
    private val fileBackend: FileBackend,
    private val avatarService: AvatarService,
    private val databaseBackend: DatabaseBackend,
    private val updateConversationUi: UpdateConversationUi,
    private val updateAccountUi: UpdateAccountUi,
    private val syncRoster: SyncRoster,
    private val updateRosterUi: UpdateRosterUi
) {
    operator fun invoke(account: Account, avatar: Avatar?, callback: UiCallback<Avatar>?) {
        val packet = service.iqGenerator.retrievePepAvatar(avatar)
        sendIqPacket(account, packet, OnIqPacketReceived { a, result ->
            synchronized(service.mInProgressAvatarFetches) {
                service.mInProgressAvatarFetches.remove(XmppConnvectionConstans.generateFetchKey(a, avatar!!))
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
                        Timber.d(a.getJid().asBareJid().toString() + ": successfully fetched pep avatar for " + avatar.owner)
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
}

@ServiceScope
class FetchAvatarVcard @Inject constructor(
    private val service: XmppConnectionService,
    private val fileBackend: FileBackend,
    private val databaseBackend: DatabaseBackend,
    private val avatarService: AvatarService,
    private val updateAccountUi: UpdateAccountUi,
    private val syncRoster: SyncRoster,
    private val updateRosterUi: UpdateRosterUi,
    private val updateConversationUi: UpdateConversationUi,
    private val find: Find,
    private val updateMucRosterUi: UpdateMucRosterUi
) {
    operator fun invoke(account: Account, avatar: Avatar, callback: UiCallback<Avatar>?) {
        val packet = service.iqGenerator.retrieveVcardAvatar(avatar)
        service.sendIqPacket(account, packet, OnIqPacketReceived { account, packet ->
            val previouslyOmittedPepFetch: Boolean
            synchronized(service.mInProgressAvatarFetches) {
                val KEY = XmppConnvectionConstans.generateFetchKey(account, avatar)
                service.mInProgressAvatarFetches.remove(KEY)
                previouslyOmittedPepFetch = service.mOmittedPepAvatarFetches.remove(KEY)
            }
            if (packet.type == IqPacket.TYPE.RESULT) {
                val vCard = packet.findChild("vCard", "vcard-temp")
                val photo = vCard?.findChild("PHOTO")
                val image = photo?.findChildContent("BINVAL")
                if (image != null) {
                    avatar.image = image
                    if (fileBackend.save(avatar)) {
                        Timber.d(account.jid.asBareJid().toString() + ": successfully fetched vCard avatar for " + avatar.owner + " omittedPep=" + previouslyOmittedPepFetch)
                        if (avatar.owner.isBareJid) {
                            if (account.jid.asBareJid() == avatar.owner && account.avatar == null) {
                                Timber.d(account.jid.asBareJid().toString() + ": had no avatar. replacing with vcard")
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
                                val user =
                                    conversation.mucOptions.findUserByFullJid(avatar.owner)
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
}

@ServiceScope
class CheckForAvatar @Inject constructor(
    private val service: XmppConnectionService,
    private val fileBackend: FileBackend,
    private val databaseBackend: DatabaseBackend,
    private val avatarService: AvatarService,
    private val fetchAvatarPep: FetchAvatarPep
) {
    operator fun invoke(account: Account, callback: UiCallback<Avatar>) {
        val packet = service.iqGenerator.retrieveAvatarMetaData(null)
        service.sendIqPacket(account, packet, OnIqPacketReceived { account, packet ->
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
}

@ServiceScope
class NotifyAccountAvatarHasChanged @Inject constructor(
    private val service: XmppConnectionService,
    private val presenceGenerator: PresenceGenerator
) {
    operator fun invoke(account: Account) {
        val connection = account.xmppConnection
        if (connection != null && connection.features.bookmarksConversion()) {
            Timber.d(account.jid.asBareJid().toString() + ": avatar changed. resending presence to online group chats")
            for (conversation in service.conversations) {
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
}

@ServiceScope
class DeleteContactOnServer @Inject constructor(
    private val service: XmppConnectionService
) {
    operator fun invoke(contact: Contact) {
        contact.resetOption(Contact.Options.PREEMPTIVE_GRANT)
        contact.resetOption(Contact.Options.DIRTY_PUSH)
        contact.setOption(Contact.Options.DIRTY_DELETE)
        val account = contact.account
        if (account.status == Account.State.ONLINE) {
            val iq = IqPacket(IqPacket.TYPE.SET)
            val item = iq.query(Namespace.ROSTER).addChild("item")
            item.setAttribute("jid", contact.jid.toString())
            item.setAttribute("subscription", "remove")
            account.xmppConnection.sendIqPacket(iq, service.defaultIqHandler)
        }
    }
}

@ServiceScope
class UpdateConversation @Inject constructor(
    private val service: XmppConnectionService,
    private val databaseBackend: DatabaseBackend
) {
    operator fun invoke(conversation: Conversation) {
        service.mDatabaseWriterExecutor.execute { databaseBackend.updateConversation(conversation) }
    }
}

@ServiceScope
class ReconnectAccount @Inject constructor(
    private val service: XmppConnectionService,
    private val createConnection: CreateConnection,
    private val hasInternetConnection: HasInternetConnection,
    private val disconnect: Disconnect,
    private val scheduleWakeUpCall: ScheduleWakeUpCall
) {
    operator fun invoke(account: Account, force: Boolean, interactive: Boolean) {
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
}

@ServiceScope
class ReconnectAccountInBackground @Inject constructor(
    private val reconnectAccount: ReconnectAccount
) {
    operator fun invoke(account: Account) {
        Thread { reconnectAccount(account, false, true) }.start()
    }
}

@ServiceScope
class Invite @Inject constructor(
    private val messageGenerator: MessageGenerator,
    private val sendMessagePacket: SendMessagePacket
) {
    operator fun invoke(conversation: Conversation, contact: Jid) {
        Timber.d(conversation.account.jid.asBareJid().toString() + ": inviting " + contact + " to " + conversation.jid.asBareJid())
        val packet = messageGenerator.invite(conversation, contact)
        sendMessagePacket(conversation.account, packet)
    }
}

@ServiceScope
class DirectInvite @Inject constructor(
    private val service: XmppConnectionService,
    private val messageGenerator: MessageGenerator,
    private val sendMessagePacket: SendMessagePacket
) {
    operator fun invoke(conversation: Conversation, jid: Jid) {
        val packet = messageGenerator.directInvite(conversation, jid)
        sendMessagePacket(conversation.account, packet)
    }
}

@ServiceScope
class ResetSendingToWaiting @Inject constructor(
    private val service: XmppConnectionService,
    private val getConversations: GetConversations,
    private val markMessage: MarkMessage
) {
    operator fun invoke(account: Account) {
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
}

@ServiceScope
class MarkMessage @Inject constructor(
    private val getConversations: GetConversations,
    private val markMessage: MarkMessage,
    private val databaseBackend: DatabaseBackend,
    private val updateConversationUi: UpdateConversationUi
) {

    @JvmOverloads
    operator fun invoke(
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
                    invoke(message, status, errorMessage)
                }
                return message
            }
        }
        return null
    }

    operator fun invoke(
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
    operator fun invoke(message: Message, status: Int, errorMessage: String? = null) {
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
}

