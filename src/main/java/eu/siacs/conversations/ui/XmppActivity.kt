package eu.siacs.conversations.ui

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.support.annotation.BoolRes
import android.support.annotation.StringRes
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.xmpp.callback.*
import eu.siacs.conversations.feature.xmpp.command.*
import eu.siacs.conversations.feature.xmpp.query.*
import eu.siacs.conversations.services.AvatarService
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.util.PresenceSelector
import eu.siacs.conversations.utils.ThemeHelper
import rocks.xmpp.addr.Jid
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.*
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
    lateinit var usingEnterKey: UsingEnterKey
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
    lateinit var setListItemBackgroundOnView: SetListItemBackgroundOnView
    @Inject
    lateinit var choosePgpSignId: ChoosePgpSignId
    @Inject
    lateinit var displayErrorDialog: DisplayErrorDialog
    @Inject
    lateinit var showAddToRosterDialog: ShowAddToRosterDialog
    @Inject
    lateinit var showAskForPresenceDialog: ShowAskForPresenceDialog
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
    lateinit var manuallyChangePresence: ManuallyChangePresence
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
    lateinit var onOpenPGPKeyPublished : OnOpenPGPKeyPublished
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

    val isDarkTheme: Boolean
        get() = ThemeHelper.isDark(mTheme)

    val isOptimizingBattery: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                return pm != null && !pm.isIgnoringBatteryOptimizations(packageName)
            } else {
                return false
            }
        }

    val isAffectedByDataSaver: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                return (cm != null
                        && cm.isActiveNetworkMetered
                        && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
            } else {
                return false
            }
        }

    val shareableUri: String?
        get() = getShareableUri(false)


    abstract fun refreshUiReal()

    abstract fun onBackendConnected()


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

    fun connectToBackend() = connectToBackend.invoke()

    override fun onStop() {
        super.onStop()
        onStop.invoke()
    }


    fun hasPgp() = hasPgp.invoke()

    fun showInstallPgpDialog() = showInstallPgpDialog.invoke()

    fun registerListeners() = registerListeners.invoke()

    fun unregisterListeners() = unregisterListeners.invoke()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        onOptionsItemSelected.invoke(item)
        return super.onOptionsItemSelected(item)
    }

    fun selectPresence(
        conversation: Conversation,
        listener: PresenceSelector.OnPresenceSelected
    ) = selectPresence.invoke(conversation, listener)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onCreate.invoke(savedInstanceState)
    }

    fun getThemeResource(
        r_attr_name: Int,
        r_drawable_def: Int
    ): Int = getThemeResource.invoke(r_attr_name, r_drawable_def)

    fun usingEnterKey(): Boolean = usingEnterKey.invoke()

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

    fun switchToConversation(
        conversation: Conversation,
        text: String?,
        asQuote: Boolean,
        nick: String?,
        pm: Boolean,
        doNotAppend: Boolean
    ) {
        switchToConversation.invoke(conversation, text, asQuote, nick, pm, doNotAppend)
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

    fun delegateUriPermissionsToService(uri: Uri) {
        delegateUriPermissionsToService.invoke(uri)

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

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    fun setListItemBackgroundOnView(view: View) {
        setListItemBackgroundOnView.invoke(view)
    }

    fun choosePgpSignId(account: Account) {
        choosePgpSignId.invoke(account)
    }

    fun displayErrorDialog(errorCode: Int) {
        displayErrorDialog.invoke(errorCode)
    }

    fun showAddToRosterDialog(contact: Contact) {
        showAddToRosterDialog.invoke(contact)
    }

    fun showAskForPresenceDialog(contact: Contact) {
        showAskForPresenceDialog.invoke(contact)
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

    fun quickPasswordEdit(
        previousValue: String,
        callback: (String) -> String?
    ) {
        quickPasswordEdit.invoke(previousValue, callback)
    }

    @SuppressLint("InflateParams")
    fun quickEdit(
        previousValue: String?,
        onValueEdited: (String) -> String?,
        @StringRes hint: Int,
        password: Boolean,
        permitEmpty: Boolean
    ) {
        quickEdit.invoke(previousValue, onValueEdited, hint, password, permitEmpty)
    }

    fun hasStoragePermission(requestCode: Int): Boolean {
        return hasStoragePermission.invoke(requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        onActivityResult.invoke(requestCode, resultCode, data)
    }

    fun copyTextToClipboard(text: String, labelResId: Int): Boolean {
        return copyTextToClipboard.invoke(text, labelResId)
    }

    fun manuallyChangePresence(): Boolean {
        return manuallyChangePresence.invoke()
    }

    open fun getShareableUri(http: Boolean): String? {
        return null
    }

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

    interface OnValueEdited : (String) -> String?

    class ConferenceInvite {
        var uuid: String? = null
        val jids = ArrayList<Jid>()

        fun execute(activity: XmppActivity): Boolean {
            val service = activity.xmppConnectionService
            val conversation = service!!.findConversationByUuid(this.uuid) ?: return false
            if (conversation.mode == Conversation.MODE_MULTI) {
                for (jid in jids) {
                    service.invite(conversation, jid)
                }
                return false
            } else {
                jids.add(conversation.jid.asBareJid())
                return service.createAdhocConference(
                    conversation.account,
                    null,
                    jids,
                    activity.adhocCallback
                )
            }
        }

        companion object {

            fun parse(data: Intent): ConferenceInvite? {
                val invite = ConferenceInvite()
                invite.uuid = data.getStringExtra(ChooseContactActivity.EXTRA_CONVERSATION)
                if (invite.uuid == null) {
                    return null
                }
                invite.jids.addAll(ChooseContactActivity.extractJabberIds(data))
                return invite
            }
        }
    }

    class BitmapWorkerTask constructor(imageView: ImageView) :
        AsyncTask<Message, Void, Bitmap>() {
        private val imageViewReference: WeakReference<ImageView> = WeakReference(imageView)
        var message: Message? = null

        override fun doInBackground(vararg params: Message): Bitmap? {
            if (isCancelled) {
                return null
            }
            message = params[0]
            return try {
                val activity = find(imageViewReference)
                if (activity?.xmppConnectionService != null) {
                    activity.xmppConnectionService.fileBackend.getThumbnail(
                        message,
                        (activity.metrics!!.density * 288).toInt(),
                        false
                    )
                } else {
                    null
                }
            } catch (e: IOException) {
                null
            }

        }

        override fun onPostExecute(bitmap: Bitmap?) {
            if (!isCancelled) {
                val imageView = imageViewReference.get()
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.setBackgroundColor(if (bitmap == null) -0xcccccd else 0x00000000)
                }
            }
        }
    }

    class AsyncDrawable constructor(
        res: Resources,
        bitmap: Bitmap?,
        bitmapWorkerTask: BitmapWorkerTask
    ) : BitmapDrawable(res, bitmap) {

        private val bitmapWorkerTaskReference = WeakReference(bitmapWorkerTask)

        val bitmapWorkerTask: BitmapWorkerTask
            get() = bitmapWorkerTaskReference.get()!!

    }

    companion object {

        const val EXTRA_ACCOUNT = "account"
        const val REQUEST_ANNOUNCE_PGP = 0x0101
        const val REQUEST_INVITE_TO_CONVERSATION = 0x0102
        const val REQUEST_CHOOSE_PGP_ID = 0x0103
        const val REQUEST_BATTERY_OP = 0x49ff
        const val FRAGMENT_TAG_DIALOG = "dialog"

        @JvmStatic
        fun cancelPotentialWork(message: Message, imageView: ImageView): Boolean {
            val bitmapWorkerTask = getBitmapWorkerTask(imageView)

            if (bitmapWorkerTask != null) {
                val oldMessage = bitmapWorkerTask.message
                if (oldMessage == null || message !== oldMessage) {
                    bitmapWorkerTask.cancel(true)
                } else {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        fun getBitmapWorkerTask(imageView: ImageView?): BitmapWorkerTask? {
            if (imageView != null) {
                val drawable = imageView.drawable
                if (drawable is AsyncDrawable) {
                    return drawable.bitmapWorkerTask
                }
            }
            return null
        }

        @JvmStatic
        fun find(viewWeakReference: WeakReference<ImageView>): XmppActivity? {
            val view = viewWeakReference.get()
            return if (view == null) null else find(view)
        }

        @JvmStatic
        fun find(view: View): XmppActivity? {
            var context = view.context
            while (context is ContextWrapper) {
                if (context is XmppActivity) {
                    return context
                }
                context = context.baseContext
            }
            return null
        }
    }
}
