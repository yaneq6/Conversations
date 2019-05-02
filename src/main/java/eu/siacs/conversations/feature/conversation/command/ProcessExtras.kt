package eu.siacs.conversations.feature.conversation.command

import android.content.Intent
import android.os.Bundle
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.util.Attachment
import io.aakit.scope.ActivityScope
import rocks.xmpp.addr.Jid
import java.util.ArrayList
import javax.inject.Inject

@ActivityScope
class ProcessExtras @Inject constructor(
    private val fragment: ConversationFragment
) : (Bundle) -> Unit {
    override fun invoke(extras: Bundle) = fragment.run {
        val downloadUuid = extras.getString(ConversationsActivity.EXTRA_DOWNLOAD_UUID)
        val text = extras.getString(Intent.EXTRA_TEXT)
        val nick = extras.getString(ConversationsActivity.EXTRA_NICK)
        val asQuote = extras.getBoolean(ConversationsActivity.EXTRA_AS_QUOTE)
        val pm = extras.getBoolean(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, false)
        val doNotAppend = extras.getBoolean(ConversationsActivity.EXTRA_DO_NOT_APPEND, false)
        val uris = extractUris(extras)
        if (uris != null && uris.isNotEmpty()) {
            val cleanedUris = cleanUris(ArrayList(uris))
            mediaPreviewAdapter!!.addMediaPreviews(
                Attachment.of(
                    getActivity(),
                    cleanedUris
                )
            )
            toggleInputMethod()
            return
        }
        if (nick != null) {
            if (pm) {
                val jid = conversation!!.jid
                try {
                    val next = Jid.of(jid.local, jid.domain, nick)
                    privateMessageWith(next)
                } catch (ignored: IllegalArgumentException) {
                    //do nothing
                }

            } else {
                val mucOptions = conversation!!.mucOptions
                if (mucOptions.participating() || conversation!!.nextCounterpart != null) {
                    highlightInConference(nick)
                }
            }
        } else {
            if (text != null && asQuote) {
                quoteText(text)
            } else {
                appendText(text, doNotAppend)
            }
        }
        val message = if (downloadUuid == null) null else conversation!!.findMessageWithFileAndUuid(downloadUuid)
        if (message != null) {
            startDownloadable(message)
        }
    }
}