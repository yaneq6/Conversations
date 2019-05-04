package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import android.view.ContextMenu
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.http.HttpDownloadConnection
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.util.ShareUtil
import eu.siacs.conversations.utils.GeoHelper
import eu.siacs.conversations.utils.MessageUtils
import eu.siacs.conversations.utils.Patterns
import eu.siacs.conversations.utils.UIHelper
import eu.siacs.conversations.xmpp.jingle.JingleConnection
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class PopulateContextMenu @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: Activity
) : (ContextMenu) -> Unit {
    override fun invoke(menu: ContextMenu) {
        val message = fragment.selectedMessage!!
        val t = message.transferable
        var relevantForCorrection = message
        while (relevantForCorrection.mergeable(relevantForCorrection.next())) {
            relevantForCorrection = relevantForCorrection.next()
        }
        if (message.type != Message.TYPE_STATUS) {

            if (message.encryption == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE || message.encryption == Message.ENCRYPTION_AXOLOTL_FAILED) {
                return
            }

            val deleted = message.isDeleted
            val encrypted =
                message.encryption == Message.ENCRYPTION_DECRYPTION_FAILED || message.encryption == Message.ENCRYPTION_PGP
            val receiving =
                message.status == Message.STATUS_RECEIVED && (t is JingleConnection || t is HttpDownloadConnection)
            activity.menuInflater.inflate(R.menu.message_context, menu)
            menu.setHeaderTitle(R.string.message_options)
            val openWith = menu.findItem(R.id.open_with)
            val copyMessage = menu.findItem(R.id.copy_message)
            val copyLink = menu.findItem(R.id.copy_link)
            val quoteMessage = menu.findItem(R.id.quote_message)
            val retryDecryption = menu.findItem(R.id.retry_decryption)
            val correctMessage = menu.findItem(R.id.correct_message)
            val shareWith = menu.findItem(R.id.share_with)
            val sendAgain = menu.findItem(R.id.send_again)
            val copyUrl = menu.findItem(R.id.copy_url)
            val downloadFile = menu.findItem(R.id.download_file)
            val cancelTransmission = menu.findItem(R.id.cancel_transmission)
            val deleteFile = menu.findItem(R.id.delete_file)
            val showErrorMessage = menu.findItem(R.id.show_error_message)
            val showError =
                message.status == Message.STATUS_SEND_FAILED && message.errorMessage != null && Message.ERROR_MESSAGE_CANCELLED != message.errorMessage
            if (!message.isFileOrImage && !encrypted && !message.isGeoUri && !message.treatAsDownloadable()) {
                copyMessage.isVisible = true
                quoteMessage.isVisible = !showError && MessageUtils.prepareQuote(message).isNotEmpty()
                val body = message.mergedBody.toString()
                if (ShareUtil.containsXmppUri(body)) {
                    copyLink.setTitle(R.string.copy_jabber_id)
                    copyLink.isVisible = true
                } else if (Patterns.AUTOLINK_WEB_URL.matcher(body).find()) {
                    copyLink.isVisible = true
                }
            }
            if (message.encryption == Message.ENCRYPTION_DECRYPTION_FAILED && !deleted) {
                retryDecryption.isVisible = true
            }
            if (!showError
                && relevantForCorrection.type == Message.TYPE_TEXT
                && !message.isGeoUri
                && relevantForCorrection.isLastCorrectableMessage
                && message.conversation is Conversation
            ) {
                correctMessage.isVisible = true
            }
            if (message.isFileOrImage && !deleted && !receiving || message.type == Message.TYPE_TEXT && !message.treatAsDownloadable()) {
                shareWith.isVisible = true
            }
            if (message.status == Message.STATUS_SEND_FAILED) {
                sendAgain.isVisible = true
            }
            if (message.hasFileOnRemoteHost()
                || message.isGeoUri
                || message.treatAsDownloadable()
                || t is HttpDownloadConnection
            ) {
                copyUrl.isVisible = true
            }
            if (message.isFileOrImage && deleted && message.hasFileOnRemoteHost()) {
                downloadFile.isVisible = true
                downloadFile.title =
                    activity.getString(
                        R.string.download_x_file,
                        UIHelper.getFileDescriptionString(activity, message)
                    )
            }
            val waitingOfferedSending = (message.status == Message.STATUS_WAITING
                    || message.status == Message.STATUS_UNSEND
                    || message.status == Message.STATUS_OFFERED)
            val cancelable = t != null && !deleted || waitingOfferedSending && message.needsUploading()
            if (cancelable) {
                cancelTransmission.isVisible = true
            }
            if (message.isFileOrImage && !deleted && !cancelable) {
                val path = message.relativeFilePath
                if (path == null || !path.startsWith("/") || FileBackend.isInDirectoryThatShouldNotBeScanned(
                        activity,
                        path
                    )
                ) {
                    deleteFile.isVisible = true
                    deleteFile.title =
                        activity.getString(
                            R.string.delete_x_file,
                            UIHelper.getFileDescriptionString(activity, message)
                        )
                }
            }
            if (showError) {
                showErrorMessage.isVisible = true
            }
            val mime = if (message.isFileOrImage) message.mimeType else null
            if (message.isGeoUri && GeoHelper.openInOsmAnd(
                    activity,
                    message
                ) || mime != null && mime.startsWith("audio/")
            ) {
                openWith.isVisible = true
            }
        }
    }
}