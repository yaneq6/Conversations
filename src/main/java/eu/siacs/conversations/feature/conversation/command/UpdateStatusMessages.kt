package eu.siacs.conversations.feature.conversation.command

import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.entities.ReadByMarker
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.utils.UIHelper
import eu.siacs.conversations.xmpp.chatstate.ChatState
import io.aakit.scope.ActivityScope
import java.util.ArrayList
import java.util.HashSet
import javax.inject.Inject

@ActivityScope
class UpdateStatusMessages @Inject constructor(
    private val fragment: ConversationFragment,
    private val updateDateSeparators: UpdateDateSeparators,
    private val showLoadMoreMessages: ShowLoadMoreMessages
) : () -> Unit {
    override fun invoke() {
        updateDateSeparators()
        synchronized(fragment.messageList) {
            val messageList = fragment.messageList
            val conversation = fragment.conversation!!
            if (showLoadMoreMessages(conversation)) {
                messageList.add(0, Message.createLoadMoreMessage(conversation))
            }
            if (conversation.mode == Conversation.MODE_SINGLE) {
                when (conversation.incomingChatState) {
                    ChatState.COMPOSING -> messageList.add(
                        Message.createStatusMessage(
                            conversation,
                            fragment.getString(R.string.contact_is_typing, conversation.name)
                        )
                    )
                    ChatState.PAUSED -> messageList.add(
                        Message.createStatusMessage(
                            conversation,
                            fragment.getString(R.string.contact_has_stopped_typing, conversation.name)
                        )
                    )
                    else -> for (i in messageList.indices.reversed()) {
                        val message = messageList[i]
                        if (message.type != Message.TYPE_STATUS) {
                            if (message.status == Message.STATUS_RECEIVED) {
                                return
                            } else {
                                if (message.status == Message.STATUS_SEND_DISPLAYED) {
                                    messageList.add(
                                        i + 1,
                                        Message.createStatusMessage(
                                            conversation,
                                            fragment.getString(
                                                R.string.contact_has_read_up_to_this_point,
                                                conversation.name
                                            )
                                        )
                                    )
                                    return
                                }
                            }
                        }
                    }
                }
            } else {
                val mucOptions = conversation.mucOptions
                val allUsers = mucOptions.users
                val addedMarkers = HashSet<ReadByMarker>()
                var state = ChatState.COMPOSING
                var users: List<MucOptions.User> = conversation.mucOptions.getUsersWithChatState(state, 5)
                if (users.isEmpty()) {
                    state = ChatState.PAUSED
                    users = conversation.mucOptions.getUsersWithChatState(state, 5)
                }
                if (mucOptions.isPrivateAndNonAnonymous) {
                    for (i in messageList.indices.reversed()) {
                        val markersForMessage = messageList[i].readByMarkers
                        val shownMarkers = ArrayList<MucOptions.User>()
                        for (marker in markersForMessage) {
                            if (!ReadByMarker.contains(marker, addedMarkers)) {
                                addedMarkers.add(marker) //may be put outside this condition. set should do dedup anyway
                                val user = mucOptions.findUser(marker)
                                if (user != null && !users.contains(user)) {
                                    shownMarkers.add(user)
                                }
                            }
                        }
                        val markerForSender = ReadByMarker.from(messageList[i])
                        val statusMessage: Message?
                        val size = shownMarkers.size
                        when {
                            size > 1 -> {
                                val body: String
                                when {
                                    size <= 4 -> body = fragment.getString(
                                        R.string.contacts_have_read_up_to_this_point,
                                        UIHelper.concatNames(shownMarkers)
                                    )
                                    ReadByMarker.allUsersRepresented(
                                        allUsers,
                                        markersForMessage,
                                        markerForSender
                                    ) -> body = fragment.getString(R.string.everyone_has_read_up_to_this_point)
                                    else -> body = fragment.getString(
                                        R.string.contacts_and_n_more_have_read_up_to_this_point,
                                        UIHelper.concatNames(shownMarkers, 3),
                                        size - 3
                                    )
                                }
                                statusMessage =
                                    Message.createStatusMessage(conversation, body)
                                statusMessage.counterparts = shownMarkers
                            }
                            size == 1 -> {
                                statusMessage = Message.createStatusMessage(
                                    conversation,
                                    fragment.getString(
                                        R.string.contact_has_read_up_to_this_point,
                                        UIHelper.getDisplayName(shownMarkers[0])
                                    )
                                )
                                statusMessage.counterpart = shownMarkers[0].fullJid
                                statusMessage.trueCounterpart = shownMarkers[0].realJid
                            }
                            else -> statusMessage = null
                        }
                        if (statusMessage != null) {
                            messageList.add(i + 1, statusMessage)
                        }
                        addedMarkers.add(markerForSender)
                        if (ReadByMarker.allUsersRepresented(allUsers, addedMarkers)) {
                            break
                        }
                    }
                }
                if (users.isNotEmpty()) {
                    val statusMessage: Message
                    if (users.size == 1) {
                        val user = users[0]
                        val id =
                            if (state == ChatState.COMPOSING) R.string.contact_is_typing else R.string.contact_has_stopped_typing
                        statusMessage =
                            Message.createStatusMessage(
                                conversation,
                                fragment.getString(id, UIHelper.getDisplayName(user))
                            )
                        statusMessage.trueCounterpart = user.realJid
                        statusMessage.counterpart = user.fullJid
                    } else {
                        val id =
                            if (state == ChatState.COMPOSING) R.string.contacts_are_typing else R.string.contacts_have_stopped_typing
                        statusMessage =
                            Message.createStatusMessage(
                                conversation,
                                fragment.getString(id, UIHelper.concatNames(users))
                            )
                        statusMessage.counterparts = users
                    }
                    messageList.add(statusMessage)
                } else ""
            }
        }
        Unit
    }
}