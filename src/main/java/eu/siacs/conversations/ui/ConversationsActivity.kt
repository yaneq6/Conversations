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


import android.content.Context
import android.content.Intent
import android.databinding.DataBindingUtil
import android.net.Uri
import android.os.Bundle
import android.support.annotation.IdRes
import android.support.v7.widget.Toolbar
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.crypto.OmemoSetting
import eu.siacs.conversations.databinding.ActivityConversationsBinding
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.feature.conversations.*
import eu.siacs.conversations.feature.conversations.di.ActivityModule
import eu.siacs.conversations.feature.conversations.di.DaggerConversationsComponent
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.interfaces.*
import eu.siacs.conversations.ui.util.ActivityResult
import eu.siacs.conversations.ui.util.ConversationMenuConfigurator
import eu.siacs.conversations.ui.util.PendingItem
import eu.siacs.conversations.xmpp.OnUpdateBlocklist
import rocks.xmpp.addr.Jid
import timber.log.Timber
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

class ConversationsActivity :
    XmppActivity(),
    OnConversationSelected,
    OnConversationArchived,
    OnConversationsListItemUpdated,
    OnConversationRead,
    OnUpdateBlocklist,
    XmppConnectionService.OnAccountUpdate,
    XmppConnectionService.OnConversationUpdate,
    XmppConnectionService.OnRosterUpdate,
    XmppConnectionService.OnShowErrorToast,
    XmppConnectionService.OnAffiliationChanged {

    var binding: ActivityConversationsBinding? = null

    var activityPaused = true

    val postponedActivityResult = PendingItem<ActivityResult>()

    @Inject
    lateinit var pendingViewIntent: PendingItem<Intent>
    @Inject
    lateinit var redirectInProcess: AtomicBoolean
    @Inject
    lateinit var fragments: XmppFragmentsInteractor
    @Inject
    lateinit var handleActivityResult: HandleActivityResultCommand
    @Inject
    lateinit var handlePermissionsResult: HandlePermissionsResultCommand
    @Inject
    lateinit var invalidateActionBarTitle: InvalidateActionBarTitleCommand
    @Inject
    lateinit var createOptionMenu: CreateOptionMenuCommand
    @Inject
    lateinit var openConversation: OpenConversationCommand
    @Inject
    lateinit var showDialogsIfMainIsOverview: ShowDialogsIfMainIsOverviewCommand
    @Inject
    lateinit var performRedirectIfNecessary: PerformRedirectIfNecessaryCommand
    @Inject
    lateinit var processViewIntent: ProcessViewIntentCommand
    @Inject
    lateinit var handleConversationArchived: HandleConversationArchivedCommand
    @Inject
    lateinit var handleNewIntent: HandleNewIntentCommand
    @Inject
    lateinit var handleOptionsItemSelected: HandleOptionsItemSelected
    @Inject
    lateinit var handleXmppUriClick: HandleXmppUriClickCommand
    @Inject
    lateinit var batteryOptimizationPreferenceKey: BatteryOptimizationPreferenceKeyQuery


    public override fun refreshUiReal() = fragments.refresh()

    internal override fun onBackendConnected() {
        if (performRedirectIfNecessary(true)) {
            return
        }
        xmppConnectionService.notificationService.setIsInForeground(true)
        val intent = pendingViewIntent.pop()
        if (intent != null) {
            if (processViewIntent(intent)) {
                if (binding!!.secondaryFragment != null) {
                    fragments.onBackendConnected(R.id.main_fragment)
                }
                invalidateActionBarTitle()
                return
            }
        }
        for (id in FRAGMENT_ID_NOTIFICATION_ORDER) {
            fragments.onBackendConnected(R.id.main_fragment)
        }

        postponedActivityResult.pop()?.let(handleActivityResult)

        invalidateActionBarTitle()
        if (binding!!.secondaryFragment != null && ConversationFragment.getConversation(this) == null) {
            val conversation = ConversationsOverviewFragment.getSuggestion(this)
            if (conversation != null) {
                openConversation(conversation)
            }
        }
        showDialogsIfMainIsOverview()
    }

    internal fun setNeverAskForBatteryOptimizationsAgain() {
        preferences.edit().putBoolean(batteryOptimizationPreferenceKey(), false).apply()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        handlePermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ActivityResult.of(requestCode, resultCode, data).let { activityResult ->
            if (xmppConnectionService != null)
                handleActivityResult(activityResult)
            else
                postponedActivityResult.push(activityResult)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DaggerConversationsComponent.builder().activityModule(ActivityModule(this)).build()(this)
        ConversationMenuConfigurator.reloadFeatures(this)
        OmemoSetting.load(this)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_conversations)
        setSupportActionBar(binding!!.toolbar as Toolbar)
        ActionBarActivity.configureActionBar(supportActionBar)
        fragmentManager.addOnBackStackChangedListener {
            invalidateActionBarTitle()
            showDialogsIfMainIsOverview()
        }
        fragments.initialize(binding!!)

        invalidateActionBarTitle()

        (savedInstanceState?.getParcelable("Intent") ?: intent).takeIf(::isViewOrShareIntent)
            ?.also(pendingViewIntent::push)
            ?.let { intent = createLauncherIntent(this) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = super
        .onCreateOptionsMenu(menu)
        .also { createOptionMenu(menu) }

    override fun onConversationSelected(conversation: Conversation) {
        clearPendingViewIntent()
        if (ConversationFragment.getConversation(this) === conversation) {
            Log.d(Config.LOGTAG, "ignore onConversationSelected() because conversation is already open")
            return
        }
        openConversation(conversation)
    }

    fun clearPendingViewIntent() = pendingViewIntent.clear()

    override fun onAffiliationChangedSuccessful(jid: Jid) {}

    override fun onAffiliationChangeFailed(jid: Jid, resId: Int) = runOnUiThread {
        Toast.makeText(
            this@ConversationsActivity,
            getString(resId, jid.asBareJid().toString()),
            Toast.LENGTH_SHORT
        ).show()
    }


    fun onXmppUriClicked(uri: Uri): Boolean = handleXmppUriClick(uri)

    override fun onOptionsItemSelected(item: MenuItem) = handleOptionsItemSelected(item) ?: super.onOptionsItemSelected(item)

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
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
        redirectInProcess.set(false)
        super.onStart()
    }

    override fun onNewIntent(intent: Intent) = handleNewIntent(intent)

    override fun onPause() {
        activityPaused = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        activityPaused = false
    }

    override fun onConversationArchived(conversation: Conversation) = handleConversationArchived(conversation)

    override fun onConversationsListItemUpdated() {
        fragmentManager.findFragmentById(R.id.main_fragment)
            ?.let { it as? ConversationsOverviewFragment }
            ?.refresh()
    }

    override fun switchToConversation(conversation: Conversation) {
        Log.d(Config.LOGTAG, "override")
        openConversation(conversation)
    }

    override fun onConversationRead(conversation: Conversation, upToUuid: String) {
        if (!activityPaused && pendingViewIntent.peek() == null) {
            xmppConnectionService.sendReadMarker(conversation, upToUuid)
        } else {
            Log.d(
                Config.LOGTAG,
                "ignoring read callback. activityPaused=" + java.lang.Boolean.toString(activityPaused)
            )
        }
    }

    override fun onAccountUpdate() = refreshUi()

    override fun onRosterUpdate() = refreshUi()

    override fun OnUpdateBlocklist(status: OnUpdateBlocklist.Status) = refreshUi()

    override fun onShowErrorToast(resId: Int) = runOnUiThread { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }

    override fun onConversationUpdate() {
        if (!performRedirectIfNecessary(false)) refreshUi()
    }

    companion object {

        const val ACTION_VIEW_CONVERSATION = "eu.siacs.conversations.action.VIEW"
        const val EXTRA_CONVERSATION = "conversationUuid"
        const val EXTRA_DOWNLOAD_UUID = "eu.siacs.conversations.download_uuid"
        const val EXTRA_AS_QUOTE = "as_quote"
        const val EXTRA_NICK = "nick"
        const val EXTRA_IS_PRIVATE_MESSAGE = "pm"
        const val EXTRA_DO_NOT_APPEND = "do_not_append"

        val VIEW_AND_SHARE_ACTIONS = Arrays.asList(
            ACTION_VIEW_CONVERSATION,
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE
        )

        const val REQUEST_OPEN_MESSAGE = 0x9876
        const val REQUEST_PLAY_PAUSE = 0x5432


        //secondary fragment (when holding the conversation, must be initialized before refreshing the overview fragment
        @IdRes
        val FRAGMENT_ID_NOTIFICATION_ORDER = intArrayOf(R.id.secondary_fragment, R.id.main_fragment)

        fun isViewOrShareIntent(intent: Intent?): Boolean {
            Timber.d("action: ${intent?.action}")
            return intent?.run { VIEW_AND_SHARE_ACTIONS.contains(action) && hasExtra(EXTRA_CONVERSATION) } ?: false
        }

        fun createLauncherIntent(context: Context): Intent {
            val intent = Intent(context, ConversationsActivity::class.java)
            intent.action = Intent.ACTION_MAIN
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            return intent
        }
    }
}


