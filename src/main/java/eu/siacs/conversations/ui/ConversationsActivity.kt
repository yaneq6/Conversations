/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui


import android.annotation.SuppressLint
import android.app.Activity
import android.app.FragmentManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.databinding.DataBindingUtil
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.support.annotation.IdRes
import android.support.v7.app.AlertDialog
import android.support.v7.widget.Toolbar
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.crypto.OmemoSetting
import eu.siacs.conversations.databinding.ActivityConversationsBinding
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.ConversationFragment.REQUEST_DECRYPT_PGP
import eu.siacs.conversations.ui.interfaces.*
import eu.siacs.conversations.ui.util.ActivityResult
import eu.siacs.conversations.ui.util.ConversationMenuConfigurator
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil
import eu.siacs.conversations.ui.util.PendingItem
import eu.siacs.conversations.utils.*
import eu.siacs.conversations.xmpp.OnUpdateBlocklist
import org.openintents.openpgp.util.OpenPgpApi
import rocks.xmpp.addr.Jid
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class ConversationsActivity : XmppActivity(), OnConversationSelected, OnConversationArchived,
    OnConversationsListItemUpdated, OnConversationRead, XmppConnectionService.OnAccountUpdate,
    XmppConnectionService.OnConversationUpdate, XmppConnectionService.OnRosterUpdate, OnUpdateBlocklist,
    XmppConnectionService.OnShowErrorToast, XmppConnectionService.OnAffiliationChanged {
    private val pendingViewIntent = PendingItem<Intent>()
    private val postponedActivityResult = PendingItem<ActivityResult>()
    private var binding: ActivityConversationsBinding? = null
    private var mActivityPaused = true
    private val mRedirectInProcess = AtomicBoolean(false)

    private val batteryOptimizationPreferenceKey: String
        get() {
            @SuppressLint("HardwareIds") val device =
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            return "show_battery_optimization" + (device ?: "")
        }

    override fun refreshUiReal() {
        for (id in FRAGMENT_ID_NOTIFICATION_ORDER) {
            refreshFragment(id)
        }
    }

    internal override fun onBackendConnected() {
        if (performRedirectIfNecessary(true)) {
            return
        }
        xmppConnectionService.notificationService.setIsInForeground(true)
        val intent = pendingViewIntent.pop()
        if (intent != null) {
            if (processViewIntent(intent)) {
                if (binding!!.secondaryFragment != null) {
                    notifyFragmentOfBackendConnected(R.id.main_fragment)
                }
                invalidateActionBarTitle()
                return
            }
        }
        for (id in FRAGMENT_ID_NOTIFICATION_ORDER) {
            notifyFragmentOfBackendConnected(id)
        }

        val activityResult = postponedActivityResult.pop()
        if (activityResult != null) {
            handleActivityResult(activityResult)
        }

        invalidateActionBarTitle()
        if (binding!!.secondaryFragment != null && ConversationFragment.getConversation(this) == null) {
            val conversation = ConversationsOverviewFragment.getSuggestion(this)
            if (conversation != null) {
                openConversation(conversation, null)
            }
        }
        showDialogsIfMainIsOverview()
    }

    private fun performRedirectIfNecessary(noAnimation: Boolean): Boolean {
        return performRedirectIfNecessary(null, noAnimation)
    }

    private fun performRedirectIfNecessary(ignore: Conversation?, noAnimation: Boolean): Boolean {
        if (xmppConnectionService == null) {
            return false
        }
        val isConversationsListEmpty = xmppConnectionService.isConversationsListEmpty(ignore)
        if (isConversationsListEmpty && mRedirectInProcess.compareAndSet(false, true)) {
            val intent = SignupUtils.getRedirectionIntent(this)
            if (noAnimation) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            runOnUiThread {
                startActivity(intent)
                if (noAnimation) {
                    overridePendingTransition(0, 0)
                }
            }
        }
        return mRedirectInProcess.get()
    }

    private fun showDialogsIfMainIsOverview() {
        if (xmppConnectionService == null) {
            return
        }
        val fragment = fragmentManager.findFragmentById(R.id.main_fragment)
        if (fragment is ConversationsOverviewFragment) {
            if (ExceptionHelper.checkForCrash(this)) {
                return
            }
            openBatteryOptimizationDialogIfNeeded()
        }
    }

    private fun setNeverAskForBatteryOptimizationsAgain() {
        preferences.edit().putBoolean(batteryOptimizationPreferenceKey, false).apply()
    }

    private fun openBatteryOptimizationDialogIfNeeded() {
        if (hasAccountWithoutPush()
            && isOptimizingBattery
            && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
            && preferences.getBoolean(batteryOptimizationPreferenceKey, true)
        ) {
            val builder = AlertDialog.Builder(this)
            builder.setTitle(R.string.battery_optimizations_enabled)
            builder.setMessage(R.string.battery_optimizations_enabled_dialog)
            builder.setPositiveButton(R.string.next) { dialog, which ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                val uri = Uri.parse("package:$packageName")
                intent.data = uri
                try {
                    startActivityForResult(intent, REQUEST_BATTERY_OP)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.device_does_not_support_battery_op, Toast.LENGTH_SHORT).show()
                }
            }
            builder.setOnDismissListener { dialog -> setNeverAskForBatteryOptimizationsAgain() }
            val dialog = builder.create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
        }
    }

    private fun hasAccountWithoutPush(): Boolean {
        for (account in xmppConnectionService.accounts) {
            if (account.status == Account.State.ONLINE && !xmppConnectionService.pushManagementService.available(account)) {
                return true
            }
        }
        return false
    }

    private fun notifyFragmentOfBackendConnected(@IdRes id: Int) {
        val fragment = fragmentManager.findFragmentById(id)
        if (fragment is OnBackendConnected) {
            (fragment as OnBackendConnected).onBackendConnected()
        }
    }

    private fun refreshFragment(@IdRes id: Int) {
        val fragment = fragmentManager.findFragmentById(id)
        if (fragment is XmppFragment) {
            fragment.refresh()
        }
    }

    private fun processViewIntent(intent: Intent): Boolean {
        val uuid = intent.getStringExtra(EXTRA_CONVERSATION)
        val conversation = if (uuid != null) xmppConnectionService.findConversationByUuid(uuid) else null
        if (conversation == null) {
            Log.d(Config.LOGTAG, "unable to view conversation with uuid:" + uuid!!)
            return false
        }
        openConversation(conversation, intent.extras)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults)
        if (grantResults.size > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                when (requestCode) {
                    REQUEST_OPEN_MESSAGE -> {
                        refreshUiReal()
                        ConversationFragment.openPendingMessage(this)
                    }
                    REQUEST_PLAY_PAUSE -> ConversationFragment.startStopPending(this)
                }
            }
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val activityResult = ActivityResult.of(requestCode, resultCode, data)
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult)
        } else {
            this.postponedActivityResult.push(activityResult)
        }
    }

    private fun handleActivityResult(activityResult: ActivityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data)
        } else {
            handleNegativeActivityResult(activityResult.requestCode)
        }
    }

    private fun handleNegativeActivityResult(requestCode: Int) {
        val conversation = ConversationFragment.getConversationReliable(this)
        when (requestCode) {
            REQUEST_DECRYPT_PGP -> conversation?.account?.pgpDecryptionService?.giveUpCurrentDecryption()
            REQUEST_BATTERY_OP -> setNeverAskForBatteryOptimizationsAgain()
        }
    }

    private fun handlePositiveActivityResult(requestCode: Int, data: Intent) {
        val conversation = ConversationFragment.getConversationReliable(this)
        if (conversation == null) {
            Log.d(Config.LOGTAG, "conversation not found")
            return
        }
        when (requestCode) {
            REQUEST_DECRYPT_PGP -> conversation.account.pgpDecryptionService.continueDecryption(data)
            XmppActivity.REQUEST_CHOOSE_PGP_ID -> {
                val id = data.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0)
                if (id != 0L) {
                    conversation.account.setPgpSignId(id)
                    announcePgp(conversation.account, null, null, onOpenPGPKeyPublished)
                } else {
                    choosePgpSignId(conversation.account)
                }
            }
            XmppActivity.REQUEST_ANNOUNCE_PGP -> announcePgp(
                conversation.account,
                conversation,
                data,
                onOpenPGPKeyPublished
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConversationMenuConfigurator.reloadFeatures(this)
        OmemoSetting.load(this)
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_conversations)
        setSupportActionBar(binding!!.toolbar as Toolbar)
        ActionBarActivity.configureActionBar(supportActionBar)
        this.fragmentManager.addOnBackStackChangedListener(FragmentManager.OnBackStackChangedListener { this.invalidateActionBarTitle() })
        this.fragmentManager.addOnBackStackChangedListener(FragmentManager.OnBackStackChangedListener { this.showDialogsIfMainIsOverview() })
        this.initializeFragments()
        this.invalidateActionBarTitle()
        val intent: Intent?
        if (savedInstanceState == null) {
            intent = getIntent()
        } else {
            intent = savedInstanceState.getParcelable("intent")
        }
        if (isViewOrShareIntent(intent)) {
            pendingViewIntent.push(intent)
            setIntent(createLauncherIntent(this))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_conversations, menu)
        AccountUtils.showHideMenuItems(menu)
        val qrCodeScanMenuItem = menu.findItem(R.id.action_scan_qr_code)
        if (qrCodeScanMenuItem != null) {
            if (isCameraFeatureAvailable) {
                val fragment = fragmentManager.findFragmentById(R.id.main_fragment)
                val visible = (resources.getBoolean(R.bool.show_qr_code_scan)
                        && fragment != null
                        && fragment is ConversationsOverviewFragment)
                qrCodeScanMenuItem.isVisible = visible
            } else {
                qrCodeScanMenuItem.isVisible = false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onConversationSelected(conversation: Conversation) {
        clearPendingViewIntent()
        if (ConversationFragment.getConversation(this) === conversation) {
            Log.d(Config.LOGTAG, "ignore onConversationSelected() because conversation is already open")
            return
        }
        openConversation(conversation, null)
    }

    fun clearPendingViewIntent() {
        if (pendingViewIntent.clear()) {
            Log.e(Config.LOGTAG, "cleared pending view intent")
        }
    }

    private fun displayToast(msg: String) {
        runOnUiThread { Toast.makeText(this@ConversationsActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onAffiliationChangedSuccessful(jid: Jid) {

    }

    override fun onAffiliationChangeFailed(jid: Jid, resId: Int) {
        displayToast(getString(resId, jid.asBareJid().toString()))
    }

    private fun openConversation(conversation: Conversation, extras: Bundle?) {
        var conversationFragment: ConversationFragment? =
            fragmentManager.findFragmentById(R.id.secondary_fragment) as? ConversationFragment
        val mainNeedsRefresh: Boolean
        if (conversationFragment == null) {
            mainNeedsRefresh = false
            val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
            if (mainFragment is ConversationFragment) {
                conversationFragment = mainFragment
            } else {
                conversationFragment = ConversationFragment()
                val fragmentTransaction = fragmentManager.beginTransaction()
                fragmentTransaction.replace(R.id.main_fragment, conversationFragment)
                fragmentTransaction.addToBackStack(null)
                try {
                    fragmentTransaction.commit()
                } catch (e: IllegalStateException) {
                    Log.w(Config.LOGTAG, "sate loss while opening conversation", e)
                    //allowing state loss is probably fine since view intents et all are already stored and a click can probably be 'ignored'
                    return
                }

            }
        } else {
            mainNeedsRefresh = true
        }
        conversationFragment.reInit(conversation, extras ?: Bundle())
        if (mainNeedsRefresh) {
            refreshFragment(R.id.main_fragment)
        } else {
            invalidateActionBarTitle()
        }
    }

    fun onXmppUriClicked(uri: Uri): Boolean {
        val xmppUri = XmppUri(uri)
        if (xmppUri.isJidValid && !xmppUri.hasFingerprints()) {
            val conversation = xmppConnectionService.findUniqueConversationByJid(xmppUri)
            if (conversation != null) {
                openConversation(conversation, null)
                return true
            }
        }
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false
        }
        when (item.itemId) {
            android.R.id.home -> {
                val fm = fragmentManager
                if (fm.backStackEntryCount > 0) {
                    try {
                        fm.popBackStack()
                    } catch (e: IllegalStateException) {
                        Log.w(Config.LOGTAG, "Unable to pop back stack after pressing home button")
                    }

                    return true
                }
            }
            R.id.action_scan_qr_code -> {
                UriHandlerActivity.scan(this)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        val pendingIntent = pendingViewIntent.peek()
        savedInstanceState.putParcelable("intent", pendingIntent ?: intent)
        super.onSaveInstanceState(savedInstanceState)
    }

    override fun onStart() {
        val theme = findTheme()
        if (this.mTheme != theme) {
            this.mSkipBackgroundBinding = true
            recreate()
        } else {
            this.mSkipBackgroundBinding = false
        }
        mRedirectInProcess.set(false)
        super.onStart()
    }

    override fun onNewIntent(intent: Intent) {
        if (isViewOrShareIntent(intent)) {
            if (xmppConnectionService != null) {
                clearPendingViewIntent()
                processViewIntent(intent)
            } else {
                pendingViewIntent.push(intent)
            }
        }
        setIntent(createLauncherIntent(this))
    }

    override fun onPause() {
        this.mActivityPaused = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        this.mActivityPaused = false
    }

    private fun initializeFragments() {
        var transaction = fragmentManager.beginTransaction()
        val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
        val secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment)
        if (mainFragment != null) {
            if (binding!!.secondaryFragment != null) {
                if (mainFragment is ConversationFragment) {
                    fragmentManager.popBackStack()
                    transaction.remove(mainFragment)
                    transaction.commit()
                    fragmentManager.executePendingTransactions()
                    transaction = fragmentManager.beginTransaction()
                    transaction.replace(R.id.secondary_fragment, mainFragment)
                    transaction.replace(R.id.main_fragment, ConversationsOverviewFragment())
                    transaction.commit()
                    return
                }
            } else {
                if (secondaryFragment is ConversationFragment) {
                    transaction.remove(secondaryFragment)
                    transaction.commit()
                    fragmentManager.executePendingTransactions()
                    transaction = fragmentManager.beginTransaction()
                    transaction.replace(R.id.main_fragment, secondaryFragment)
                    transaction.addToBackStack(null)
                    transaction.commit()
                    return
                }
            }
        } else {
            transaction.replace(R.id.main_fragment, ConversationsOverviewFragment())
        }
        if (binding!!.secondaryFragment != null && secondaryFragment == null) {
            transaction.replace(R.id.secondary_fragment, ConversationFragment())
        }
        transaction.commit()
    }

    private fun invalidateActionBarTitle() {
        val actionBar = supportActionBar
        if (actionBar != null) {
            val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
            if (mainFragment is ConversationFragment) {
                val conversation = mainFragment.conversation
                if (conversation != null) {
                    actionBar.title = EmojiWrapper.transform(conversation.name)
                    actionBar.setDisplayHomeAsUpEnabled(true)
                    return
                }
            }
            actionBar.setTitle(R.string.app_name)
            actionBar.setDisplayHomeAsUpEnabled(false)
        }
    }

    override fun onConversationArchived(conversation: Conversation) {
        if (performRedirectIfNecessary(conversation, false)) {
            return
        }
        val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
        if (mainFragment is ConversationFragment) {
            try {
                fragmentManager.popBackStack()
            } catch (e: IllegalStateException) {
                Log.w(Config.LOGTAG, "state loss while popping back state after archiving conversation", e)
                //this usually means activity is no longer active; meaning on the next open we will run through this again
            }

            return
        }
        val secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment)
        if (secondaryFragment is ConversationFragment) {
            if (secondaryFragment.conversation === conversation) {
                val suggestion = ConversationsOverviewFragment.getSuggestion(this, conversation)
                if (suggestion != null) {
                    openConversation(suggestion, null)
                }
            }
        }
    }

    override fun onConversationsListItemUpdated() {
        val fragment = fragmentManager.findFragmentById(R.id.main_fragment)
        if (fragment is ConversationsOverviewFragment) {
            fragment.refresh()
        }
    }

    override fun switchToConversation(conversation: Conversation) {
        Log.d(Config.LOGTAG, "override")
        openConversation(conversation, null)
    }

    override fun onConversationRead(conversation: Conversation, upToUuid: String) {
        if (!mActivityPaused && pendingViewIntent.peek() == null) {
            xmppConnectionService.sendReadMarker(conversation, upToUuid)
        } else {
            Log.d(
                Config.LOGTAG,
                "ignoring read callback. mActivityPaused=" + java.lang.Boolean.toString(mActivityPaused)
            )
        }
    }

    override fun onAccountUpdate() {
        this.refreshUi()
    }

    override fun onConversationUpdate() {
        if (performRedirectIfNecessary(false)) {
            return
        }
        this.refreshUi()
    }

    override fun onRosterUpdate() {
        this.refreshUi()
    }

    override fun OnUpdateBlocklist(status: OnUpdateBlocklist.Status) {
        this.refreshUi()
    }

    override fun onShowErrorToast(resId: Int) {
        runOnUiThread { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }
    }

    companion object {

        const val ACTION_VIEW_CONVERSATION = "eu.siacs.conversations.action.VIEW"
        const val EXTRA_CONVERSATION = "conversationUuid"
        const val EXTRA_DOWNLOAD_UUID = "eu.siacs.conversations.download_uuid"
        const val EXTRA_AS_QUOTE = "as_quote"
        const val EXTRA_NICK = "nick"
        const val EXTRA_IS_PRIVATE_MESSAGE = "pm"
        const val EXTRA_DO_NOT_APPEND = "do_not_append"

        private val VIEW_AND_SHARE_ACTIONS = Arrays.asList(
            ACTION_VIEW_CONVERSATION,
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE
        )

        const val REQUEST_OPEN_MESSAGE = 0x9876
        const val REQUEST_PLAY_PAUSE = 0x5432


        //secondary fragment (when holding the conversation, must be initialized before refreshing the overview fragment
        @IdRes
        private val FRAGMENT_ID_NOTIFICATION_ORDER = intArrayOf(R.id.secondary_fragment, R.id.main_fragment)

        private fun isViewOrShareIntent(i: Intent?): Boolean {
            Log.d(Config.LOGTAG, "action: " + i?.action)
            return i != null && VIEW_AND_SHARE_ACTIONS.contains(i.action) && i.hasExtra(EXTRA_CONVERSATION)
        }

        private fun createLauncherIntent(context: Context): Intent {
            val intent = Intent(context, ConversationsActivity::class.java)
            intent.action = Intent.ACTION_MAIN
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            return intent
        }
    }
}
