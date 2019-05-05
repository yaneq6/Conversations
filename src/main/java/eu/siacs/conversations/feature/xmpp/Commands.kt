package eu.siacs.conversations.feature.xmpp

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.databinding.DataBindingUtil
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.support.annotation.BoolRes
import android.support.annotation.StringRes
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatDelegate
import android.text.InputType
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
import eu.siacs.conversations.services.BarcodeProvider
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.*
import eu.siacs.conversations.ui.service.EmojiService
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil
import eu.siacs.conversations.ui.util.PresenceSelector
import eu.siacs.conversations.ui.util.SoftKeyboardUtils
import eu.siacs.conversations.utils.AccountUtils
import eu.siacs.conversations.utils.ExceptionHelper
import eu.siacs.conversations.utils.ThemeHelper
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated
import eu.siacs.conversations.xmpp.OnUpdateBlocklist
import io.aakit.scope.ActivityScope
import rocks.xmpp.addr.Jid
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import javax.inject.Inject


@ActivityScope
class HideToast @Inject constructor(private val activity: XmppActivity) {
    operator fun invoke(): Unit = activity.run {
        if (mToast != null) {
            mToast!!.cancel()
        }
    }
}

@ActivityScope
class ReplaceToast @Inject constructor(private val activity: XmppActivity) {
    operator fun invoke(msg: String) {
        invoke(msg, true)
    }

    operator fun invoke(msg: String, showlong: Boolean) = activity.run {
        hideToast()
        mToast =
            Toast.makeText(activity, msg, if (showlong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        mToast!!.show()
    }
}


@ActivityScope
class RefreshUi @Inject constructor(private val activity: XmppActivity) {
    operator fun invoke(): Unit = activity.run {
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
}

@ActivityScope
class OnStart @Inject constructor(private val activity: XmppActivity) {
    operator fun invoke(): Unit = activity.run {
        if (!xmppConnectionServiceBound) {
            if (activity.mSkipBackgroundBinding) {
                Timber.d("skipping background binding")
            } else {
                connectToBackend()
            }
        } else {
            activity.registerListeners()
            activity.onBackendConnected()
        }
    }
}

@ActivityScope
class ConnectToBackend @Inject constructor(private val activity: XmppActivity) {
    operator fun invoke(): Unit = activity.run {
        val intent = Intent(activity, XmppConnectionService::class.java)
        intent.action = "ui"
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            Timber.w("unable to start service from " + javaClass.simpleName)
        }

        bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }
}

@ActivityScope
class OnStop @Inject constructor(private val activity: XmppActivity) {
    operator fun invoke(): Unit = activity.run {
        if (xmppConnectionServiceBound) {
            activity.unregisterListeners()
            unbindService(mConnection)
            xmppConnectionServiceBound = false
        }
    }
}

@ActivityScope
class HasPgp @Inject constructor(
    private val activity: XmppActivity
) : () -> Boolean {
    override fun invoke(): Boolean = activity.run {
        xmppConnectionService.pgpEngine != null
    }
}

@ActivityScope
class ShowInstallPgpDialog @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(): Unit = activity.run {
        val builder = AlertDialog.Builder(activity)
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
                    activity,
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
            val manager = applicationContext.packageManager
            val infos = manager.queryIntentActivities(marketIntent, 0)
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
}

@ActivityScope
class RegisterListeners @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(): Unit = activity.run {
        if (activity is XmppConnectionService.OnConversationUpdate) {
            activity.xmppConnectionService.setOnConversationListChangedListener(activity as XmppConnectionService.OnConversationUpdate)
        }
        if (activity is XmppConnectionService.OnAccountUpdate) {
            activity.xmppConnectionService.setOnAccountListChangedListener(activity as XmppConnectionService.OnAccountUpdate)
        }
        if (activity is XmppConnectionService.OnCaptchaRequested) {
            activity.xmppConnectionService.setOnCaptchaRequestedListener(activity as XmppConnectionService.OnCaptchaRequested)
        }
        if (activity is XmppConnectionService.OnRosterUpdate) {
            activity.xmppConnectionService.setOnRosterUpdateListener(activity as XmppConnectionService.OnRosterUpdate)
        }
        if (activity is XmppConnectionService.OnMucRosterUpdate) {
            activity.xmppConnectionService.setOnMucRosterUpdateListener(activity as XmppConnectionService.OnMucRosterUpdate)
        }
        if (activity is OnUpdateBlocklist) {
            activity.xmppConnectionService.setOnUpdateBlocklistListener(activity as OnUpdateBlocklist)
        }
        if (activity is XmppConnectionService.OnShowErrorToast) {
            activity.xmppConnectionService.setOnShowErrorToastListener(activity as XmppConnectionService.OnShowErrorToast)
        }
        if (activity is OnKeyStatusUpdated) {
            activity.xmppConnectionService.setOnKeyStatusUpdatedListener(activity as OnKeyStatusUpdated)
        }
    }
}

@ActivityScope
class UnregisterListeners @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(): Unit = activity.run {
        if (activity is XmppConnectionService.OnConversationUpdate) {
            activity.xmppConnectionService.removeOnConversationListChangedListener(activity as XmppConnectionService.OnConversationUpdate)
        }
        if (activity is XmppConnectionService.OnAccountUpdate) {
            activity.xmppConnectionService.removeOnAccountListChangedListener(activity as XmppConnectionService.OnAccountUpdate)
        }
        if (activity is XmppConnectionService.OnCaptchaRequested) {
            activity.xmppConnectionService.removeOnCaptchaRequestedListener(activity as XmppConnectionService.OnCaptchaRequested)
        }
        if (activity is XmppConnectionService.OnRosterUpdate) {
            activity.xmppConnectionService.removeOnRosterUpdateListener(activity as XmppConnectionService.OnRosterUpdate)
        }
        if (activity is XmppConnectionService.OnMucRosterUpdate) {
            activity.xmppConnectionService.removeOnMucRosterUpdateListener(activity as XmppConnectionService.OnMucRosterUpdate)
        }
        if (activity is OnUpdateBlocklist) {
            activity.xmppConnectionService.removeOnUpdateBlocklistListener(activity as OnUpdateBlocklist)
        }
        if (activity is XmppConnectionService.OnShowErrorToast) {
            activity.xmppConnectionService.removeOnShowErrorToastListener(activity as XmppConnectionService.OnShowErrorToast)
        }
        if (activity is OnKeyStatusUpdated) {
            activity.xmppConnectionService.removeOnNewKeysAvailableListener(activity as OnKeyStatusUpdated)
        }
    }
}

@ActivityScope
class OnOptionsItemSelected @Inject constructor(
    private val activity: XmppActivity,
    private val showQrCode: ShowQrCode
) {
    operator fun invoke(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> activity.startActivity(
                Intent(
                    activity,
                    SettingsActivity::class.java
                )
            )
            R.id.action_accounts -> AccountUtils.launchManageAccounts(activity)
            R.id.action_account -> AccountUtils.launchManageAccount(activity)
            android.R.id.home -> activity.finish()
            R.id.action_show_qr_code -> showQrCode()
            else -> null
        } != null
    }
}

@ActivityScope
class SelectPresence @Inject constructor(
    private val activity: XmppActivity,
    private val showAddToRosterDialog: ShowAddToRosterDialog,
    private val showAskForPresenceDialog: ShowAskForPresenceDialog
) {
    operator fun invoke(conversation: Conversation, listener: PresenceSelector.OnPresenceSelected) {
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
                    PresenceSelector.warnMutualPresenceSubscription(
                        activity,
                        conversation,
                        listener
                    )
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
                PresenceSelector.showPresenceSelectionDialog(activity, conversation, listener)
            }
        }
    }
}

@ActivityScope
class OnCreate @Inject constructor(
    private val activity: XmppActivity,
    private val resources: Resources,
    private val packageManager: PackageManager,
    private val usingEnterKey: UsingEnterKey
) {

    operator fun invoke(savedInstanceState: Bundle?) {
        activity.volumeControlStream = AudioManager.STREAM_NOTIFICATION
        activity.metrics = resources.displayMetrics
        ExceptionHelper.init(activity.applicationContext)
        EmojiService(activity).init()
        activity.isCameraFeatureAvailable =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        activity.mTheme = activity.findTheme()
        activity.setTheme(activity.mTheme)

        activity.mUsingEnterKey = usingEnterKey()
    }
}

@ActivityScope
class GetThemeResource @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(r_attr_name: Int, r_drawable_def: Int): Int {
        val attrs = intArrayOf(r_attr_name)
        val ta = activity.theme.obtainStyledAttributes(attrs)

        val res = ta.getResourceId(0, r_drawable_def)
        ta.recycle()

        return res
    }
}

@ActivityScope
class UsingEnterKey @Inject constructor(
    private val getBooleanPreference: GetBooleanPreference
) {
    operator fun invoke(): Boolean {
        return getBooleanPreference("display_enter_key", R.bool.display_enter_key)
    }
}

@ActivityScope
class GetBooleanPreference @Inject constructor(
    private val activity: XmppActivity,
    private val resources: Resources
) {
    operator fun invoke(name: String, @BoolRes res: Int): Boolean {
        return activity.preferences.getBoolean(name, resources.getBoolean(res))
    }
}

@ActivityScope
class SwitchToConversation @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(conversation: Conversation) {
        invoke(conversation, null)
    }

    operator fun invoke(conversation: Conversation, text: String?) {
        invoke(conversation, text, false, null, false, false)
    }

    operator fun invoke(
        conversation: Conversation,
        text: String?,
        asQuote: Boolean,
        nick: String?,
        pm: Boolean,
        doNotAppend: Boolean
    ) {
        val intent = Intent(activity, ConversationsActivity::class.java)
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
        activity.startActivity(intent)
        activity.finish()
    }
}

@ActivityScope
class SwitchToConversationAndQuote @Inject constructor(
    private val switchToConversation: SwitchToConversation
) {
    operator fun invoke(conversation: Conversation, text: String) {
        switchToConversation(conversation, text, true, null, false, false)
    }
}


@ActivityScope
class SwitchToConversationDoNotAppend @Inject constructor(
    private val switchToConversation: SwitchToConversation
) {
    operator fun invoke(conversation: Conversation, text: String) {
        switchToConversation(conversation, text, false, null, false, true)
    }
}

@ActivityScope
class HighlightInMuc @Inject constructor(
    private val switchToConversation: SwitchToConversation
) {
    operator fun invoke(conversation: Conversation, nick: String) {
        switchToConversation(conversation, null, false, nick, false, false)
    }
}

@ActivityScope
class PrivateMsgInMuc @Inject constructor(
    private val switchToConversation: SwitchToConversation
) {
    operator fun invoke(conversation: Conversation, nick: String) {
        switchToConversation(conversation, null, false, nick, true, false)
    }
}

@ActivityScope
class SwitchToContactDetails @Inject constructor(
    private val activity: Activity
) {
    operator fun invoke(contact: Contact, messageFingerprint: String? = null) {
        val intent = Intent(activity, ContactDetailsActivity::class.java)
        intent.action = ContactDetailsActivity.ACTION_VIEW_CONTACT
        intent.putExtra(XmppActivity.EXTRA_ACCOUNT, contact.account.jid.asBareJid().toString())
        intent.putExtra("contact", contact.jid.toString())
        intent.putExtra("fingerprint", messageFingerprint)
        activity.startActivity(intent)
    }
}

@ActivityScope
class SwitchToAccount @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(account: Account, fingerprint: String) {
        invoke(account, false, fingerprint)
    }

    operator fun invoke(account: Account, init: Boolean = false, fingerprint: String? = null) {
        val intent = Intent(activity, EditAccountActivity::class.java)
        intent.putExtra("jid", account.jid.asBareJid().toString())
        intent.putExtra("init", init)
        if (init) {
            intent.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        if (fingerprint != null) {
            intent.putExtra("fingerprint", fingerprint)
        }
        activity.startActivity(intent)
        if (init) {
            activity.overridePendingTransition(0, 0)
        }
    }
}

@ActivityScope
class DelegateUriPermissionsToService @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(uri: Uri) {
        val intent = Intent(activity, XmppConnectionService::class.java)
        intent.action = Intent.ACTION_SEND
        intent.data = uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startService(intent)
        } catch (e: Exception) {
            Timber.e("unable to delegate uri permission $e")
        }

    }
}

@ActivityScope
class InviteToConversation @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(conversation: Conversation) {
        activity.startActivityForResult(
            ChooseContactActivity.create(activity, conversation),
            XmppActivity.REQUEST_INVITE_TO_CONVERSATION
        )
    }
}

@ActivityScope
class AnnouncePgp @Inject constructor(
    private val activity: XmppActivity,
    private val choosePgpSignId: ChoosePgpSignId,
    private val manuallyChangePresence: ManuallyChangePresence,
    private val displayErrorDialog: DisplayErrorDialog
) {

    operator fun invoke(
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
            activity.xmppConnectionService.pgpEngine!!.generateSignature(
                intent,
                account,
                status,
                object : UiCallback<String> {

                    override fun userInputRequried(pi: PendingIntent, signature: String) {
                        try {
                            activity.startIntentSenderForResult(
                                pi.intentSender,
                                XmppActivity.REQUEST_ANNOUNCE_PGP,
                                null,
                                0,
                                0,
                                0
                            )
                        } catch (ignored: IntentSender.SendIntentException) {
                        }

                    }

                    override fun success(signature: String) {
                        account.pgpSignature = signature
                        val xmppConnectionService = activity.xmppConnectionService
                        xmppConnectionService.databaseBackend.updateAccount(account)
                        xmppConnectionService.sendPresence(account)
                        if (conversation != null) {
                            conversation.nextEncryption = Message.ENCRYPTION_PGP
                            xmppConnectionService.updateConversation(conversation)
                        }
                        if (onSuccess != null) {
                            activity.runOnUiThread(onSuccess)
                        }
                    }

                    override fun error(error: Int, signature: String) {
                        if (error == 0) {
                            account.setPgpSignId(0)
                            account.unsetPgpSignature()
                            activity.xmppConnectionService.databaseBackend.updateAccount(account)
                            choosePgpSignId(account)
                        } else {
                            displayErrorDialog(error)
                        }
                    }
                })
        }
    }
}

@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
@ActivityScope
class SetListItemBackgroundOnView @Inject constructor(
    private val resources: Resources
) {

    operator fun invoke(view: View) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.JELLY_BEAN) {
            view.setBackgroundDrawable(resources.getDrawable(R.drawable.greybackground))
        } else {
            view.background = resources.getDrawable(R.drawable.greybackground)
        }
    }
}

@ActivityScope
class ChoosePgpSignId @Inject constructor(
    private val activity: XmppActivity

) {
    operator fun invoke(account: Account) {
        activity.xmppConnectionService.pgpEngine!!.chooseKey(
            account,
            object : UiCallback<Account> {
                override fun success(account1: Account) {}

                override fun error(errorCode: Int, `object`: Account) {

                }

                override fun userInputRequried(pi: PendingIntent, `object`: Account) {
                    try {
                        activity.startIntentSenderForResult(
                            pi.intentSender,
                            XmppActivity.REQUEST_CHOOSE_PGP_ID, null, 0, 0, 0
                        )
                    } catch (ignored: IntentSender.SendIntentException) {
                    }

                }
            })
    }
}

@ActivityScope
class DisplayErrorDialog @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(errorCode: Int) {
        activity.runOnUiThread {
            val builder = AlertDialog.Builder(activity)
            builder.setIconAttribute(android.R.attr.alertDialogIcon)
            builder.setTitle(activity.getString(R.string.error))
            builder.setMessage(errorCode)
            builder.setNeutralButton(R.string.accept, null)
            builder.create().show()
        }

    }
}

@ActivityScope
class ShowAddToRosterDialog @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(contact: Contact) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(contact.jid.toString())
        builder.setMessage(activity.getString(R.string.not_in_roster))
        builder.setNegativeButton(activity.getString(R.string.cancel), null)
        builder.setPositiveButton(activity.getString(R.string.add_contact)) { dialog, which ->
            activity.xmppConnectionService.createContact(
                contact,
                true
            )
        }
        builder.create().show()
    }
}

@ActivityScope
class ShowAskForPresenceDialog @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(contact: Contact) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(contact.jid.toString())
        builder.setMessage(R.string.request_presence_updates)
        builder.setNegativeButton(R.string.cancel, null)
        builder.setPositiveButton(
            R.string.request_now
        ) { dialog, which ->
            if (activity.xmppConnectionServiceBound) {
                activity.xmppConnectionService.sendPresencePacket(
                    contact.account,
                    activity.xmppConnectionService
                        .presenceGenerator
                        .requestPresenceUpdatesFrom(contact)
                )
            }
        }
        builder.create().show()
    }
}

@ActivityScope
class QuickEdit @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(
        previousValue: String,
        @StringRes hint: Int,
        callback: XmppActivity.OnValueEdited
    ) {
        invoke(previousValue, callback, hint, password = false, permitEmpty = false)
    }

    operator fun invoke(
        previousValue: String,
        @StringRes hint: Int,
        onValueEdited: (String) -> String?,
        permitEmpty: Boolean
    ) {
        invoke(previousValue, onValueEdited, hint, false, permitEmpty)
    }

    @SuppressLint("InflateParams")
    operator fun invoke(
        previousValue: String?,
        onValueEdited: (String) -> String?,
        @StringRes hint: Int,
        password: Boolean,
        permitEmpty: Boolean
    ) {
        val builder = AlertDialog.Builder(activity)
        val binding = DataBindingUtil.inflate<DialogQuickeditBinding>(
            activity.layoutInflater,
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
            binding.inputLayout.hint = activity.getString(hint)
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
}


@ActivityScope
class QuickPasswordEdit @Inject constructor(
    private val quickEdit: QuickEdit
) {
    operator fun invoke(previousValue: String, onValueEdited: (String) -> String?) {
        quickEdit(previousValue, onValueEdited, R.string.password, password = true, permitEmpty = false)
    }

}

@ActivityScope
class HasStoragePermission @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(requestCode: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    requestCode
                )
                false
            } else {
                true
            }
        } else {
            true
        }
}

@ActivityScope
class OnActivityResult @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == XmppActivity.REQUEST_INVITE_TO_CONVERSATION && resultCode == Activity.RESULT_OK) {
            activity.mPendingConferenceInvite = XmppActivity.ConferenceInvite.parse(data!!)
            if (activity.xmppConnectionServiceBound && activity.mPendingConferenceInvite != null) {
                if (activity.mPendingConferenceInvite!!.execute(activity)) {
                    activity.mToast =
                        Toast.makeText(activity, R.string.creating_conference, Toast.LENGTH_LONG)
                    activity.mToast!!.show()
                }
                activity.mPendingConferenceInvite = null
            }
        }
    }
}

@ActivityScope
class CopyTextToClipboard @Inject constructor(
    private val activity: XmppActivity,
    private val resources: Resources
) {
    operator fun invoke(text: String, labelResId: Int): Boolean {
        val mClipBoardManager =
            activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val label = resources.getString(labelResId)
        if (mClipBoardManager != null) {
            val mClipData = ClipData.newPlainText(label, text)
            mClipBoardManager.primaryClip = mClipData
            return true
        }
        return false
    }
}

@ActivityScope
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

@ActivityScope
class GetShareableUri @Inject constructor() {
    operator fun invoke(http: Boolean): String? {
        return null
    }
}

@ActivityScope
class ShareLink @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(http: Boolean) {
        val uri = activity.getShareableUri(http)
        if (uri == null || uri.isEmpty()) {
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, activity.getShareableUri(http))
        try {
            activity.startActivity(
                Intent.createChooser(
                    intent,
                    activity.getText(R.string.share_uri_with)
                )
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT)
                .show()
        }

    }
}

@ActivityScope
class LaunchOpenKeyChain @Inject constructor(
    private val activity: XmppActivity
) {

    operator fun invoke(keyId: Long) {
        val pgp = activity.xmppConnectionService.pgpEngine
        try {
            activity.startIntentSenderForResult(
                pgp!!.getIntentForKey(keyId).intentSender, 0, null, 0,
                0, 0
            )
        } catch (e: Throwable) {
            Toast.makeText(activity, R.string.openpgp_error, Toast.LENGTH_SHORT).show()
        }
    }
}

@ActivityScope
class FindTheme @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(): Int {
        return ThemeHelper.find(activity)
    }
}

@ActivityScope
class OnMenuOpened @Inject constructor() {
    operator fun invoke(id: Int, menu: Menu?) {
        if (id == AppCompatDelegate.FEATURE_SUPPORT_ACTION_BAR && menu != null) {
            MenuDoubleTabUtil.recordMenuOpen()
        }
    }
}

@ActivityScope
class ShowQrCode @Inject constructor(
   private val activity: XmppActivity
) {

    @JvmOverloads
    operator fun invoke(uri: String? = activity.shareableUri) {
        if (uri == null || uri.isEmpty()) {
            return
        }
        val size = Point()
        activity.windowManager.defaultDisplay.getSize(size)
        val width = if (size.x < size.y) size.x else size.y
        val bitmap = BarcodeProvider.create2dBarcodeBitmap(uri, width)
        val view = ImageView(activity)
        view.setBackgroundColor(Color.WHITE)
        view.setImageBitmap(bitmap)
        val builder = AlertDialog.Builder(activity)
        builder.setView(view)
        builder.create().show()
    }
}

@ActivityScope
class ExtractAccount @Inject constructor(
    private val activity: XmppActivity
) {

    operator fun invoke(intent: Intent?): Account? {
        val jid = intent?.getStringExtra(XmppActivity.EXTRA_ACCOUNT)
        return try {
            if (jid != null) activity.xmppConnectionService.findAccountByJid(Jid.of(jid)) else null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

@ActivityScope
class LoadBitmap @Inject constructor(
    private val activity: XmppActivity,
    private val resources: Resources
) {
    operator fun invoke(message: Message, imageView: ImageView) {
        var bm: Bitmap?
        try {
            bm = activity.xmppConnectionService.fileBackend.getThumbnail(
                message,
                (activity.metrics!!.density * 288).toInt(),
                true
            )
        } catch (e: IOException) {
            bm = null
        }

        if (bm != null) {
            XmppActivity.cancelPotentialWork(message, imageView)
            imageView.setImageBitmap(bm)
            imageView.setBackgroundColor(0x00000000)
        } else {
            if (XmppActivity.cancelPotentialWork(message, imageView)) {
                imageView.setBackgroundColor(-0xcccccd)
                imageView.setImageDrawable(null)
                val task = XmppActivity.BitmapWorkerTask(imageView)
                val asyncDrawable = XmppActivity.AsyncDrawable(
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
}