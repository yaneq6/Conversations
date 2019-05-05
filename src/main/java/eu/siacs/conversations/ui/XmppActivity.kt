package eu.siacs.conversations.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.support.annotation.BoolRes
import android.support.annotation.StringRes
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.Toast
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.di.ActivityModule
import eu.siacs.conversations.feature.xmpp.ConferenceInvite
import eu.siacs.conversations.feature.xmpp.callback.*
import eu.siacs.conversations.feature.xmpp.command.*
import eu.siacs.conversations.feature.xmpp.di.DaggerXmppActivityComponent
import eu.siacs.conversations.feature.xmpp.query.*
import eu.siacs.conversations.services.AvatarService
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.utils.ThemeHelper
import javax.inject.Inject

abstract class XmppActivity : ActionBarActivity() {

    @Inject
    lateinit var hideToast: HideToast
    @Inject
    lateinit var replaceToast: ReplaceToast
    @Inject
    lateinit var refreshUi: RefreshUi
    @Inject
    lateinit var onStart: OnStart
    @Inject
    lateinit var connectToBackend: ConnectToBackend
    @Inject
    lateinit var onStop: OnStop
    @Inject
    lateinit var hasPgp: HasPgp
    @Inject
    lateinit var showInstallPgpDialog: ShowInstallPgpDialog
    @Inject
    lateinit var registerListeners: RegisterListeners
    @Inject
    lateinit var unregisterListeners: UnregisterListeners
    @Inject
    lateinit var onOptionsItemSelected: OnOptionsItemSelected
    @Inject
    lateinit var selectPresence: SelectPresence
    @Inject
    lateinit var getThemeResource: GetThemeResource
    @Inject
    lateinit var switchToConversation: SwitchToConversation
    @Inject
    lateinit var switchToConversationAndQuote: SwitchToConversationAndQuote
    @Inject
    lateinit var switchToConversationDoNotAppend: SwitchToConversationDoNotAppend
    @Inject
    lateinit var highlightInMuc: HighlightInMuc
    @Inject
    lateinit var privateMsgInMuc: PrivateMsgInMuc
    @Inject
    lateinit var switchToContactDetails: SwitchToContactDetails
    @Inject
    lateinit var switchToAccount: SwitchToAccount
    @Inject
    lateinit var delegateUriPermissionsToService: DelegateUriPermissionsToService
    @Inject
    lateinit var inviteToConversation: InviteToConversation
    @Inject
    lateinit var announcePgp: AnnouncePgp
    @Inject
    lateinit var choosePgpSignId: ChoosePgpSignId
    @Inject
    lateinit var showAddToRosterDialog: ShowAddToRosterDialog
    @Inject
    lateinit var quickEdit: QuickEdit
    @Inject
    lateinit var quickPasswordEdit: QuickPasswordEdit
    @Inject
    lateinit var hasStoragePermission: HasStoragePermission
    @Inject
    lateinit var onActivityResult: OnActivityResult
    @Inject
    lateinit var copyTextToClipboard: CopyTextToClipboard
    @Inject
    lateinit var shareLink: ShareLink
    @Inject
    lateinit var launchOpenKeyChain: LaunchOpenKeyChain
    @Inject
    lateinit var findTheme: FindTheme
    @Inject
    lateinit var onMenuOpened: OnMenuOpened
    @Inject
    lateinit var extractAccount: ExtractAccount
    @Inject
    lateinit var showQrCode: ShowQrCode
    @Inject
    lateinit var loadBitmap: LoadBitmap
    @Inject
    lateinit var onOpenPGPKeyPublished: OnOpenPGPKeyPublished
    @Inject
    lateinit var connection: Connection
    @Inject
    lateinit var refreshUiRunnable: RefreshUiRunnable
    @Inject
    lateinit var adhocCallback: AdhocCallback

    lateinit var xmppConnectionService: XmppConnectionService
    @JvmField
    var xmppConnectionServiceBound = false
    @JvmField
    var isCameraFeatureAvailable = false
    @JvmField
    var mTheme: Int = 0
    @JvmField
    var mUsingEnterKey = false
    @JvmField
    var mToast: Toast? = null
    @JvmField
    var mPendingConferenceInvite: ConferenceInvite? = null
    @JvmField
    var metrics: DisplayMetrics? = null
    @JvmField
    var mLastUiRefresh: Long = 0
    @JvmField
    var mSkipBackgroundBinding = false
    @JvmField
    val mRefreshUiHandler = Handler()


    val preferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    val getBooleanPreference: GetBooleanPreference by lazy {
        GetBooleanPreference(
            activity = this,
            resources = resources
        )
    }

    val onCreate
        get() = OnCreate(
            activity = this,
            resources = resources,
            packageManager = packageManager,
            usingEnterKey = UsingEnterKey(
                getBooleanPreference = getBooleanPreference
            )
        )

    val isDarkTheme: Boolean get() = IsDarkTheme(this)()

    val isOptimizingBattery: Boolean get() = IsOptimizingBattery(this)()

    val isAffectedByDataSaver: Boolean get() = IsAffectedByDataSaver(this)()

    val shareableUri: String? get() = getShareableUri(false)


    abstract fun refreshUiReal()

    abstract fun onBackendConnected()

    open fun injectDependencies() {
        DaggerXmppActivityComponent
            .builder()
            .activityModule(ActivityModule(this))
            .build()
            .invoke(this)
    }

    open fun hideToast() = hideToast.invoke()

    open fun replaceToast(msg: String) = replaceToast.invoke(msg)

    fun replaceToast(msg: String, showlong: Boolean) = replaceToast.invoke(msg, showlong)

    fun refreshUi() {
        refreshUi.invoke()
    }

    override fun onStart() {
        super.onStart()
        onStart.invoke()
    }

    override fun onStop() {
        super.onStop()
        onStop.invoke()
    }


    fun hasPgp() = hasPgp.invoke()

    fun showInstallPgpDialog() = showInstallPgpDialog.invoke()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        onOptionsItemSelected.invoke(item)
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onCreate.invoke(savedInstanceState)
    }

    fun getThemeResource(
        r_attr_name: Int,
        r_drawable_def: Int
    ): Int = getThemeResource.invoke(r_attr_name, r_drawable_def)

    fun getBooleanPreference(
        name: String,
        @BoolRes res: Int
    ): Boolean = getBooleanPreference.invoke(name, res)

    open fun switchToConversation(conversation: Conversation) {
        switchToConversation.invoke(conversation, null)
    }


    fun switchToConversationAndQuote(
        conversation: Conversation,
        text: String
    ) {
        switchToConversationAndQuote.invoke(conversation, text)
    }

    fun switchToConversation(
        conversation: Conversation,
        text: String?
    ) {
        switchToConversation.invoke(conversation, text)
    }

    fun switchToConversationDoNotAppend(
        conversation: Conversation,
        text: String
    ) {
        switchToConversationDoNotAppend.invoke(conversation, text)
    }

    fun highlightInMuc(
        conversation: Conversation,
        nick: String
    ) {
        highlightInMuc.invoke(conversation, nick)
    }

    fun privateMsgInMuc(
        conversation: Conversation,
        nick: String
    ) {
        privateMsgInMuc.invoke(conversation, nick)
    }

    @JvmOverloads
    fun switchToContactDetails(
        contact: Contact,
        messageFingerprint: String? = null
    ) {
        switchToContactDetails.invoke(contact, messageFingerprint)
    }

    fun switchToAccount(account: Account, fingerprint: String) {
        switchToAccount.invoke(account, fingerprint)
    }

    @JvmOverloads
    fun switchToAccount(account: Account, init: Boolean = false, fingerprint: String? = null) {
        switchToAccount.invoke(account, init, fingerprint)
    }

    fun inviteToConversation(conversation: Conversation) {
        inviteToConversation.invoke(conversation)
    }

    fun announcePgp(
        account: Account,
        conversation: Conversation?,
        intent: Intent?,
        onSuccess: Runnable?
    ) {
        announcePgp.invoke(account, conversation, intent, onSuccess)
    }

    fun choosePgpSignId(account: Account) {
        choosePgpSignId.invoke(account)
    }

    fun showAddToRosterDialog(contact: Contact) {
        showAddToRosterDialog.invoke(contact)
    }

    fun quickEdit(
        previousValue: String,
        @StringRes hint: Int,
        callback: OnValueEdited
    ) {
        quickEdit.invoke(previousValue, hint, callback)
    }

    fun quickEdit(
        previousValue: String,
        @StringRes hint: Int,
        callback: OnValueEdited,
        permitEmpty: Boolean
    ) {
        quickEdit.invoke(previousValue, hint, callback, permitEmpty)
    }

    fun hasStoragePermission(requestCode: Int): Boolean =
        hasStoragePermission.invoke(requestCode)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        onActivityResult.invoke(requestCode, resultCode, data)
    }

    fun copyTextToClipboard(text: String, labelResId: Int): Boolean =
        copyTextToClipboard.invoke(text, labelResId)

    open fun getShareableUri(http: Boolean): String? = null

    fun shareLink(http: Boolean) {
        shareLink.invoke(http)
    }

    fun launchOpenKeyChain(keyId: Long) {
        launchOpenKeyChain.invoke(keyId)
    }

    fun findTheme(): Int = ThemeHelper.find(this)

    override fun onMenuOpened(id: Int, menu: Menu?): Boolean {
        onMenuOpened.invoke(id, menu)
        return super.onMenuOpened(id, menu)
    }

    @JvmOverloads
    fun showQrCode(uri: String? = shareableUri) {
        showQrCode.invoke(uri)
    }

    fun extractAccount(intent: Intent?): Account? = extractAccount.invoke(intent)

    fun avatarService(): AvatarService = xmppConnectionService.avatarService

    fun loadBitmap(message: Message, imageView: ImageView) {
        loadBitmap.invoke(message, imageView)
    }
}