package eu.siacs.conversations.features;

import android.content.Intent;

public abstract class StartConversation {
    public static final String EXTRA_INVITE_URI = "eu.siacs.conversations.invite_uri";
    public static final String ACTIVITY_CLASS_NAME = "eu.siacs.conversations.ui.StartConversationActivity";
    public static Class<?> ACTIVITY_CLASS;
    static {
        try {
            ACTIVITY_CLASS = Class.forName(ACTIVITY_CLASS_NAME);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static void addInviteUri(Intent to, Intent from) {
        if (from != null && from.hasExtra(EXTRA_INVITE_URI)) {
            to.putExtra(EXTRA_INVITE_URI, from.getStringExtra(EXTRA_INVITE_URI));
        }
    }
}
