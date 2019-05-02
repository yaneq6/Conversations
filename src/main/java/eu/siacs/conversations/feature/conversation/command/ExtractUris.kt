package eu.siacs.conversations.feature.conversation.command

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ExtractUris @Inject constructor() : (Bundle) -> List<Uri>? {
    override fun invoke(extras: Bundle): List<Uri>? {
        val uris = extras.getParcelableArrayList<Uri>(Intent.EXTRA_STREAM)
        if (uris != null) {
            return uris
        }
        val uri = extras.getParcelable<Uri>(Intent.EXTRA_STREAM)
        return if (uri != null) {
            listOf(uri)
        } else {
            null
        }
    }
}