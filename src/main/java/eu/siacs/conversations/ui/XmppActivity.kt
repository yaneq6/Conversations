package eu.siacs.conversations.ui

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.IntentSender.SendIntentException
import android.content.pm.PackageManager
import android.content.res.Resources
import android.databinding.DataBindingUtil
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.support.annotation.BoolRes
import android.support.annotation.StringRes
import android.support.v7.app.AlertDialog
import android.support.v7.app.AlertDialog.Builder
import android.support.v7.app.AppCompatDelegate
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.DialogQuickeditBinding
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.AvatarService
import eu.siacs.conversations.services.BarcodeProvider
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.services.XmppConnectionService.XmppConnectionBinder
import eu.siacs.conversations.ui.service.EmojiService
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil
import eu.siacs.conversations.ui.util.PresenceSelector
import eu.siacs.conversations.ui.util.SoftKeyboardUtils
import eu.siacs.conversations.utils.AccountUtils
import eu.siacs.conversations.utils.ExceptionHelper
import eu.siacs.conversations.utils.ThemeHelper
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated
import eu.siacs.conversations.xmpp.OnUpdateBlocklist
import rocks.xmpp.addr.Jid
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.RejectedExecutionException

abstract class XmppActivity : ActionBarActivity() {

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
    val onOpenPGPKeyPublished = Runnable {
        Toast.makeText(this@XmppActivity, R.string.openpgp_has_been_published, Toast.LENGTH_SHORT)
            .show()
    }
    @JvmField
    var mPendingConferenceInvite: ConferenceInvite? = null
    @JvmField
    var mConnection: ServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as XmppConnectionBinder
            xmppConnectionService = binder.service
            xmppConnectionServiceBound = true
            registerListeners()
            onBackendConnected()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            xmppConnectionServiceBound = false
        }
    }
    @JvmField
    var metrics: DisplayMetrics? = null
    @JvmField
    var mLastUiRefresh: Long = 0
    val mRefreshUiHandler = Handler()
    val mRefreshUiRunnable = {
        mLastUiRefresh = SystemClock.elapsedRealtime()
        refreshUiReal()
    }
    val adhocCallback = object : UiCallback<Conversation> {
        override fun success(conversation: Conversation) {
            runOnUiThread {
                switchToConversation(conversation)
                hideToast()
            }
        }

        override fun error(errorCode: Int, `object`: Conversation) {
            runOnUiThread { replaceToast(getString(errorCode)) }
        }

        override fun userInputRequried(pi: PendingIntent, `object`: Conversation) {

        }
    }
    @JvmField
    var mSkipBackgroundBinding = false

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

    val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(applicationContext)

    val shareableUri: String?
        get() = getShareableUri(false)


    abstract fun refreshUiReal()

    abstract fun onBackendConnected()


    open fun hideToast() {
        if (mToast != null) {
            mToast!!.cancel()
        }
    }

    open fun replaceToast(msg: String) {
        replaceToast(msg, true)
    }

    fun replaceToast(msg: String, showlong: Boolean) {
        hideToast()
        mToast = Toast.makeText(this, msg, if (showlong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        mToast!!.show()
    }

    fun refreshUi() {
        val diff = SystemClock.elapsedRealtime() - mLastUiRefresh
        if (diff > Config.REFRESH_UI_INTERVAL) {
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable)
            runOnUiThread(mRefreshUiRunnable)
        } else {
            val next = Config.REFRESH_UI_INTERVAL - diff
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable)
            mRefreshUiHandler.postDelayed(mRefreshUiRunnable, next)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!xmppConnectionServiceBound) {
            if (this.mSkipBackgroundBinding) {
                Log.d(Config.LOGTAG, "skipping background binding")
            } else {
                connectToBackend()
            }
        } else {
            this.registerListeners()
            this.onBackendConnected()
        }
    }

    fun connectToBackend() {
        val intent = Intent(this, XmppConnectionService::class.java)
        intent.action = "ui"
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            Log.w(Config.LOGTAG, "unable to start service from " + javaClass.simpleName)
        }

        bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (xmppConnectionServiceBound) {
            this.unregisterListeners()
            unbindService(mConnection)
            xmppConnectionServiceBound = false
        }
    }


    fun hasPgp(): Boolean {
        return xmppConnectionService.pgpEngine != null
    }

    fun showInstallPgpDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.openkeychain_required))
        builder.setIconAttribute(android.R.attr.alertDialogIcon)
        builder.setMessage(getText(R.string.openkeychain_required_long))
        builder.setNegativeButton(getString(R.string.cancel), null)
        builder.setNeutralButton(
            getString(R.string.restart)
        ) { dialog, which ->
            if (xmppConnectionServiceBound) {
                unbindService(mConnection)
                xmppConnectionServiceBound = false
            }
            stopService(
                Intent(
                    this@XmppActivity,
                    XmppConnectionService::class.java
                )
            )
            finish()
        }
        builder.setPositiveButton(
            getString(R.string.install)
        ) { dialog, which ->
            var uri = Uri
                .parse("market://details?id=org.sufficientlysecure.keychain")
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                uri
            )
            val manager = applicationContext
                .packageManager
            val infos = manager
                .queryIntentActivities(marketIntent, 0)
            if (infos.size > 0) {
                startActivity(marketIntent)
            } else {
                uri = Uri.parse("http://www.openkeychain.org/")
                val browserIntent = Intent(
                    Intent.ACTION_VIEW, uri
                )
                startActivity(browserIntent)
            }
            finish()
        }
        builder.create().show()
    }

    fun registerListeners() {
        if (this is XmppConnectionService.OnConversationUpdate) {
            this.xmppConnectionService.setOnConversationListChangedListener(this as XmppConnectionService.OnConversationUpdate)
        }
        if (this is XmppConnectionService.OnAccountUpdate) {
            this.xmppConnectionService.setOnAccountListChangedListener(this as XmppConnectionService.OnAccountUpdate)
        }
        if (this is XmppConnectionService.OnCaptchaRequested) {
            this.xmppConnectionService.setOnCaptchaRequestedListener(this as XmppConnectionService.OnCaptchaRequested)
        }
        if (this is XmppConnectionService.OnRosterUpdate) {
            this.xmppConnectionService.setOnRosterUpdateListener(this as XmppConnectionService.OnRosterUpdate)
        }
        if (this is XmppConnectionService.OnMucRosterUpdate) {
            this.xmppConnectionService.setOnMucRosterUpdateListener(this as XmppConnectionService.OnMucRosterUpdate)
        }
        if (this is OnUpdateBlocklist) {
            this.xmppConnectionService.setOnUpdateBlocklistListener(this as OnUpdateBlocklist)
        }
        if (this is XmppConnectionService.OnShowErrorToast) {
            this.xmppConnectionService.setOnShowErrorToastListener(this as XmppConnectionService.OnShowErrorToast)
        }
        if (this is OnKeyStatusUpdated) {
            this.xmppConnectionService.setOnKeyStatusUpdatedListener(this as OnKeyStatusUpdated)
        }
    }

    fun unregisterListeners() {
        if (this is XmppConnectionService.OnConversationUpdate) {
            this.xmppConnectionService.removeOnConversationListChangedListener(this as XmppConnectionService.OnConversationUpdate)
        }
        if (this is XmppConnectionService.OnAccountUpdate) {
            this.xmppConnectionService.removeOnAccountListChangedListener(this as XmppConnectionService.OnAccountUpdate)
        }
        if (this is XmppConnectionService.OnCaptchaRequested) {
            this.xmppConnectionService.removeOnCaptchaRequestedListener(this as XmppConnectionService.OnCaptchaRequested)
        }
        if (this is XmppConnectionService.OnRosterUpdate) {
            this.xmppConnectionService.removeOnRosterUpdateListener(this as XmppConnectionService.OnRosterUpdate)
        }
        if (this is XmppConnectionService.OnMucRosterUpdate) {
            this.xmppConnectionService.removeOnMucRosterUpdateListener(this as XmppConnectionService.OnMucRosterUpdate)
        }
        if (this is OnUpdateBlocklist) {
            this.xmppConnectionService.removeOnUpdateBlocklistListener(this as OnUpdateBlocklist)
        }
        if (this is XmppConnectionService.OnShowErrorToast) {
            this.xmppConnectionService.removeOnShowErrorToastListener(this as XmppConnectionService.OnShowErrorToast)
        }
        if (this is OnKeyStatusUpdated) {
            this.xmppConnectionService.removeOnNewKeysAvailableListener(this as OnKeyStatusUpdated)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_accounts -> AccountUtils.launchManageAccounts(this)
            R.id.action_account -> AccountUtils.launchManageAccount(this)
            android.R.id.home -> finish()
            R.id.action_show_qr_code -> showQrCode()
        }
        return super.onOptionsItemSelected(item)
    }

    fun selectPresence(conversation: Conversation, listener: PresenceSelector.OnPresenceSelected) {
        val contact = conversation.contact
        if (!contact.showInRoster()) {
            showAddToRosterDialog(conversation.contact)
        } else {
            val presences = contact.presences
            if (presences.size() == 0) {
                if (!contact.getOption(Contact.Options.TO)
                    && !contact.getOption(Contact.Options.ASKING)
                    && contact.account.status == Account.State.ONLINE
                ) {
                    showAskForPresenceDialog(contact)
                } else if (!contact.getOption(Contact.Options.TO) || !contact.getOption(Contact.Options.FROM)) {
                    PresenceSelector.warnMutualPresenceSubscription(this, conversation, listener)
                } else {
                    conversation.nextCounterpart = null
                    listener.onPresenceSelected()
                }
            } else if (presences.size() == 1) {
                val presence = presences.toResourceArray()[0]
                try {
                    conversation.nextCounterpart =
                        Jid.of(contact.jid.local, contact.jid.domain, presence)
                } catch (e: IllegalArgumentException) {
                    conversation.nextCounterpart = null
                }

                listener.onPresenceSelected()
            } else {
                PresenceSelector.showPresenceSelectionDialog(this, conversation, listener)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_NOTIFICATION
        metrics = resources.displayMetrics
        ExceptionHelper.init(applicationContext)
        EmojiService(this).init()
        this.isCameraFeatureAvailable =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        this.mTheme = findTheme()
        setTheme(this.mTheme)

        this.mUsingEnterKey = usingEnterKey()
    }

    fun getThemeResource(r_attr_name: Int, r_drawable_def: Int): Int {
        val attrs = intArrayOf(r_attr_name)
        val ta = this.theme.obtainStyledAttributes(attrs)

        val res = ta.getResourceId(0, r_drawable_def)
        ta.recycle()

        return res
    }

    fun usingEnterKey(): Boolean {
        return getBooleanPreference("display_enter_key", R.bool.display_enter_key)
    }

    fun getBooleanPreference(name: String, @BoolRes res: Int): Boolean {
        return preferences.getBoolean(name, resources.getBoolean(res))
    }

    open fun switchToConversation(conversation: Conversation) {
        switchToConversation(conversation, null)
    }

    fun switchToConversationAndQuote(conversation: Conversation, text: String) {
        switchToConversation(conversation, text, true, null, false, false)
    }

    fun switchToConversation(conversation: Conversation, text: String?) {
        switchToConversation(conversation, text, false, null, false, false)
    }

    fun switchToConversationDoNotAppend(conversation: Conversation, text: String) {
        switchToConversation(conversation, text, false, null, false, true)
    }

    fun highlightInMuc(conversation: Conversation, nick: String) {
        switchToConversation(conversation, null, false, nick, false, false)
    }

    fun privateMsgInMuc(conversation: Conversation, nick: String) {
        switchToConversation(conversation, null, false, nick, true, false)
    }

    fun switchToConversation(
        conversation: Conversation,
        text: String?,
        asQuote: Boolean,
        nick: String?,
        pm: Boolean,
        doNotAppend: Boolean
    ) {
        val intent = Intent(this, ConversationsActivity::class.java)
        intent.action = ConversationsActivity.ACTION_VIEW_CONVERSATION
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.uuid)
        if (text != null) {
            intent.putExtra(Intent.EXTRA_TEXT, text)
            if (asQuote) {
                intent.putExtra(ConversationsActivity.EXTRA_AS_QUOTE, true)
            }
        }
        if (nick != null) {
            intent.putExtra(ConversationsActivity.EXTRA_NICK, nick)
            intent.putExtra(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, pm)
        }
        if (doNotAppend) {
            intent.putExtra(ConversationsActivity.EXTRA_DO_NOT_APPEND, true)
        }
        intent.flags = intent.flags or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    @JvmOverloads
    fun switchToContactDetails(contact: Contact, messageFingerprint: String? = null) {
        val intent = Intent(this, ContactDetailsActivity::class.java)
        intent.action = ContactDetailsActivity.ACTION_VIEW_CONTACT
        intent.putExtra(EXTRA_ACCOUNT, contact.account.jid.asBareJid().toString())
        intent.putExtra("contact", contact.jid.toString())
        intent.putExtra("fingerprint", messageFingerprint)
        startActivity(intent)
    }

    fun switchToAccount(account: Account, fingerprint: String) {
        switchToAccount(account, false, fingerprint)
    }

    @JvmOverloads
    fun switchToAccount(account: Account, init: Boolean = false, fingerprint: String? = null) {
        val intent = Intent(this, EditAccountActivity::class.java)
        intent.putExtra("jid", account.jid.asBareJid().toString())
        intent.putExtra("init", init)
        if (init) {
            intent.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        if (fingerprint != null) {
            intent.putExtra("fingerprint", fingerprint)
        }
        startActivity(intent)
        if (init) {
            overridePendingTransition(0, 0)
        }
    }

    fun delegateUriPermissionsToService(uri: Uri) {
        val intent = Intent(this, XmppConnectionService::class.java)
        intent.action = Intent.ACTION_SEND
        intent.data = uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(Config.LOGTAG, "unable to delegate uri permission", e)
        }

    }

    fun inviteToConversation(conversation: Conversation) {
        startActivityForResult(
            ChooseContactActivity.create(this, conversation),
            REQUEST_INVITE_TO_CONVERSATION
        )
    }

    fun announcePgp(
        account: Account,
        conversation: Conversation?,
        intent: Intent?,
        onSuccess: Runnable?
    ) {
        if (account.pgpId == 0L) {
            choosePgpSignId(account)
        } else {
            var status: String? = null
            if (manuallyChangePresence()) {
                status = account.presenceStatusMessage
            }
            if (status == null) {
                status = ""
            }
            xmppConnectionService.pgpEngine!!.generateSignature(
                intent,
                account,
                status,
                object : UiCallback<String> {

                    override fun userInputRequried(pi: PendingIntent, signature: String) {
                        try {
                            startIntentSenderForResult(
                                pi.intentSender,
                                REQUEST_ANNOUNCE_PGP,
                                null,
                                0,
                                0,
                                0
                            )
                        } catch (ignored: SendIntentException) {
                        }

                    }

                    override fun success(signature: String) {
                        account.pgpSignature = signature
                        xmppConnectionService.databaseBackend.updateAccount(account)
                        xmppConnectionService.sendPresence(account)
                        if (conversation != null) {
                            conversation.nextEncryption = Message.ENCRYPTION_PGP
                            xmppConnectionService.updateConversation(conversation)
                            refreshUi()
                        }
                        if (onSuccess != null) {
                            runOnUiThread(onSuccess)
                        }
                    }

                    override fun error(error: Int, signature: String) {
                        if (error == 0) {
                            account.setPgpSignId(0)
                            account.unsetPgpSignature()
                            xmppConnectionService.databaseBackend.updateAccount(account)
                            choosePgpSignId(account)
                        } else {
                            displayErrorDialog(error)
                        }
                    }
                })
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    fun setListItemBackgroundOnView(view: View) {
        val sdk = android.os.Build.VERSION.SDK_INT
        if (sdk < android.os.Build.VERSION_CODES.JELLY_BEAN) {
            view.setBackgroundDrawable(resources.getDrawable(R.drawable.greybackground))
        } else {
            view.background = resources.getDrawable(R.drawable.greybackground)
        }
    }

    fun choosePgpSignId(account: Account) {
        xmppConnectionService.pgpEngine!!.chooseKey(account, object : UiCallback<Account> {
            override fun success(account1: Account) {}

            override fun error(errorCode: Int, `object`: Account) {

            }

            override fun userInputRequried(pi: PendingIntent, `object`: Account) {
                try {
                    startIntentSenderForResult(
                        pi.intentSender,
                        REQUEST_CHOOSE_PGP_ID, null, 0, 0, 0
                    )
                } catch (ignored: SendIntentException) {
                }

            }
        })
    }

    fun displayErrorDialog(errorCode: Int) {
        runOnUiThread {
            val builder = Builder(this@XmppActivity)
            builder.setIconAttribute(android.R.attr.alertDialogIcon)
            builder.setTitle(getString(R.string.error))
            builder.setMessage(errorCode)
            builder.setNeutralButton(R.string.accept, null)
            builder.create().show()
        }

    }

    fun showAddToRosterDialog(contact: Contact) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(contact.jid.toString())
        builder.setMessage(getString(R.string.not_in_roster))
        builder.setNegativeButton(getString(R.string.cancel), null)
        builder.setPositiveButton(getString(R.string.add_contact)) { dialog, which ->
            xmppConnectionService.createContact(
                contact,
                true
            )
        }
        builder.create().show()
    }

    fun showAskForPresenceDialog(contact: Contact) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(contact.jid.toString())
        builder.setMessage(R.string.request_presence_updates)
        builder.setNegativeButton(R.string.cancel, null)
        builder.setPositiveButton(
            R.string.request_now
        ) { dialog, which ->
            if (xmppConnectionServiceBound) {
                xmppConnectionService.sendPresencePacket(
                    contact
                        .account, xmppConnectionService
                        .presenceGenerator
                        .requestPresenceUpdatesFrom(contact)
                )
            }
        }
        builder.create().show()
    }

    fun quickEdit(previousValue: String, @StringRes hint: Int, callback: OnValueEdited) {
        quickEdit(previousValue, callback, hint, false, false)
    }

    fun quickEdit(
        previousValue: String, @StringRes hint: Int,
        callback: OnValueEdited,
        permitEmpty: Boolean
    ) {
        quickEdit(previousValue, callback, hint, false, permitEmpty)
    }

    fun quickPasswordEdit(previousValue: String, callback: (String) -> String?) {
        quickEdit(previousValue, callback, R.string.password, true, false)
    }

    @SuppressLint("InflateParams")
    fun quickEdit(
        previousValue: String?,
        onValueEdited: (String) -> String?,
        @StringRes hint: Int,
        password: Boolean,
        permitEmpty: Boolean
    ) {
        val builder = AlertDialog.Builder(this)
        val binding = DataBindingUtil.inflate<DialogQuickeditBinding>(
            layoutInflater,
            R.layout.dialog_quickedit,
            null,
            false
        )
        if (password) {
            binding.inputEditText.inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        builder.setPositiveButton(R.string.accept, null)
        if (hint != 0) {
            binding.inputLayout.hint = getString(hint)
        }
        binding.inputEditText.requestFocus()
        if (previousValue != null) {
            binding.inputEditText.text!!.append(previousValue)
        }
        builder.setView(binding.root)
        builder.setNegativeButton(R.string.cancel, null)
        val dialog = builder.create()
        dialog.setOnShowListener { SoftKeyboardUtils.showKeyboard(binding.inputEditText) }
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val value = binding.inputEditText.text!!.toString()
            if (value != previousValue && (value.trim { it <= ' ' }.isNotEmpty() || permitEmpty)) {
                val error = onValueEdited(value)
                if (error != null) {
                    binding.inputLayout.error = error
                    return@setOnClickListener
                }
            }
            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText)
            dialog.dismiss()
        }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText)
            dialog.dismiss()
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText)
        }
    }

    fun hasStoragePermission(requestCode: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), requestCode)
                return false
            } else {
                return true
            }
        } else {
            return true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INVITE_TO_CONVERSATION && resultCode == Activity.RESULT_OK) {
            mPendingConferenceInvite = ConferenceInvite.parse(data!!)
            if (xmppConnectionServiceBound && mPendingConferenceInvite != null) {
                if (mPendingConferenceInvite!!.execute(this)) {
                    mToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG)
                    mToast!!.show()
                }
                mPendingConferenceInvite = null
            }
        }
    }

    fun copyTextToClipboard(text: String, labelResId: Int): Boolean {
        val mClipBoardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val label = resources.getString(labelResId)
        if (mClipBoardManager != null) {
            val mClipData = ClipData.newPlainText(label, text)
            mClipBoardManager.primaryClip = mClipData
            return true
        }
        return false
    }

    fun manuallyChangePresence(): Boolean {
        return getBooleanPreference(
            SettingsActivity.MANUALLY_CHANGE_PRESENCE,
            R.bool.manually_change_presence
        )
    }

    open fun getShareableUri(http: Boolean): String? {
        return null
    }

    fun shareLink(http: Boolean) {
        val uri = getShareableUri(http)
        if (uri == null || uri.isEmpty()) {
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, getShareableUri(http))
        try {
            startActivity(Intent.createChooser(intent, getText(R.string.share_uri_with)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT).show()
        }

    }

    fun launchOpenKeyChain(keyId: Long) {
        val pgp = this@XmppActivity.xmppConnectionService.pgpEngine
        try {
            startIntentSenderForResult(
                pgp!!.getIntentForKey(keyId).intentSender, 0, null, 0,
                0, 0
            )
        } catch (e: Throwable) {
            Toast.makeText(this@XmppActivity, R.string.openpgp_error, Toast.LENGTH_SHORT).show()
        }

    }

    public override fun onResume() {
        super.onResume()
    }

    fun findTheme(): Int {
        return ThemeHelper.find(this)
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onMenuOpened(id: Int, menu: Menu?): Boolean {
        if (id == AppCompatDelegate.FEATURE_SUPPORT_ACTION_BAR && menu != null) {
            MenuDoubleTabUtil.recordMenuOpen()
        }
        return super.onMenuOpened(id, menu)
    }

    @JvmOverloads
    fun showQrCode(uri: String? = shareableUri) {
        if (uri == null || uri.isEmpty()) {
            return
        }
        val size = Point()
        windowManager.defaultDisplay.getSize(size)
        val width = if (size.x < size.y) size.x else size.y
        val bitmap = BarcodeProvider.create2dBarcodeBitmap(uri, width)
        val view = ImageView(this)
        view.setBackgroundColor(Color.WHITE)
        view.setImageBitmap(bitmap)
        val builder = AlertDialog.Builder(this)
        builder.setView(view)
        builder.create().show()
    }

    fun extractAccount(intent: Intent?): Account? {
        val jid = intent?.getStringExtra(EXTRA_ACCOUNT)
        try {
            return if (jid != null) xmppConnectionService.findAccountByJid(Jid.of(jid)) else null
        } catch (e: IllegalArgumentException) {
            return null
        }

    }

    fun avatarService(): AvatarService {
        return xmppConnectionService.avatarService
    }

    fun loadBitmap(message: Message, imageView: ImageView) {
        var bm: Bitmap?
        try {
            bm = xmppConnectionService.fileBackend.getThumbnail(
                message,
                (metrics!!.density * 288).toInt(),
                true
            )
        } catch (e: IOException) {
            bm = null
        }

        if (bm != null) {
            cancelPotentialWork(message, imageView)
            imageView.setImageBitmap(bm)
            imageView.setBackgroundColor(0x00000000)
        } else {
            if (cancelPotentialWork(message, imageView)) {
                imageView.setBackgroundColor(-0xcccccd)
                imageView.setImageDrawable(null)
                val task = BitmapWorkerTask(imageView)
                val asyncDrawable = AsyncDrawable(
                    resources, null, task
                )
                imageView.setImageDrawable(asyncDrawable)
                try {
                    task.execute(message)
                } catch (ignored: RejectedExecutionException) {
                    ignored.printStackTrace()
                }

            }
        }
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
