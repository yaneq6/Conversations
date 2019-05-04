package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.utils.MessageUtils
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class QuoteMessage @Inject constructor(
    private val quoteText: QuoteText
) : (Message) -> Unit {
    override fun invoke(message: Message) {
        quoteText(MessageUtils.prepareQuote(message))
    }
}