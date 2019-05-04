package eu.siacs.conversations.feature.conversation.command

import android.view.MenuItem
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.util.ShareUtil
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnContextItemSelected @Inject constructor(
    private val activity: ConversationsActivity,
    private val fragment: ConversationFragment,
    private val correctMessage: CorrectMessage,
    private val quoteMessage: QuoteMessage,
    private val resendMessage: ResendMessage,
    private val startDownloadable: StartDownloadable,
    private val cancelTransmission: CancelTransmission,
    private val retryDecryption: RetryDecryption,
    private val deleteFile: DeleteFile,
    private val showErrorMessage: ShowErrorMessage,
    private val openWith: OpenWith
) {
    operator fun invoke(item: MenuItem): Boolean =
        fragment.selectedMessage?.let { selectedMessage ->
            when (item.itemId) {
                R.id.share_with -> ShareUtil.share(
                    activity,
                    selectedMessage
                )
                R.id.correct_message -> correctMessage(selectedMessage)
                R.id.copy_message -> ShareUtil.copyToClipboard(
                    activity,
                    selectedMessage
                )
                R.id.copy_link -> ShareUtil.copyLinkToClipboard(
                    activity,
                    selectedMessage
                )
                R.id.quote_message -> quoteMessage(selectedMessage)
                R.id.send_again -> resendMessage(selectedMessage)
                R.id.copy_url -> ShareUtil.copyUrlToClipboard(
                    activity,
                    selectedMessage
                )
                R.id.download_file -> startDownloadable(selectedMessage)
                R.id.cancel_transmission -> cancelTransmission(selectedMessage)
                R.id.retry_decryption -> retryDecryption(selectedMessage)
                R.id.delete_file -> deleteFile(selectedMessage)
                R.id.show_error_message -> showErrorMessage(selectedMessage)
                R.id.open_with -> openWith(selectedMessage)
                else -> null
            }
        } != null
}