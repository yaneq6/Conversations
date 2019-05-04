package eu.siacs.conversations.feature.conversation.command

import android.databinding.DataBindingUtil
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.FragmentConversationBinding
import eu.siacs.conversations.feature.conversation.di.ConversationModule
import eu.siacs.conversations.feature.conversation.di.DaggerConversationComponent
import eu.siacs.conversations.feature.di.ActivityModule
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.adapter.MediaPreviewAdapter
import eu.siacs.conversations.ui.adapter.MessageAdapter
import eu.siacs.conversations.ui.util.EditMessageActionModeCallback
import eu.siacs.conversations.utils.StylingHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnCreateView @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment
) {
    operator fun invoke(
        inflater: LayoutInflater,
        container: ViewGroup?,
        extras: Bundle?
    ): View? = DataBindingUtil.inflate<FragmentConversationBinding>(
        inflater,
        R.layout.fragment_conversation,
        container,
        false
    ).apply {
        root.setOnClickListener(null) //TODO why the fuck did we do this?

        val mediaPreviewAdapter = MediaPreviewAdapter(fragment)

        val messageListAdapter = MessageAdapter(
            activity as XmppActivity,
            fragment.messageList
        ).apply {
            setOnContactPictureClicked(fragment)
            setOnContactPictureLongClicked(fragment)
            setOnQuoteListener { fragment.quoteText(it) }
        }

        fragment.binding = this
        fragment.mediaPreviewAdapter = mediaPreviewAdapter
        fragment.messageListAdapter = messageListAdapter


        DaggerConversationComponent.builder()
            .activityModule(ActivityModule(activity))
            .conversationModule(ConversationModule(fragment))
            .build()(fragment)

        messagesView.transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL

        messagesView.adapter = messageListAdapter
        mediaPreview.adapter = mediaPreviewAdapter

        textinput.addTextChangedListener(StylingHelper.MessageEditorStyler(textinput))

        textinput.setOnEditorActionListener(fragment.editorActionListener)
        textinput.setRichContentListener(arrayOf("image/*"), fragment.editorContentListener)

        fragment.registerForContextMenu(messagesView)

        textSendButton.setOnClickListener(fragment.sendButtonListener)

        scrollToBottomButton.setOnClickListener(fragment.scrollButtonListener)
        messagesView.setOnScrollListener(fragment.onScrollListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            textinput.customInsertionActionModeCallback = EditMessageActionModeCallback(textinput)
        }

        fragment.run { reInit(conversation!!, extras) }
    }.root
}