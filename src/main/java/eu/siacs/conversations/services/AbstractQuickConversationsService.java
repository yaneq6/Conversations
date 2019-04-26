package eu.siacs.conversations.services;

public abstract class AbstractQuickConversationsService {

    protected final XmppConnectionService service;

    public AbstractQuickConversationsService(XmppConnectionService service) {
        this.service = service;
    }

    public abstract void considerSync();

    public static boolean isQuicksy() {
        return false;
    }

    public static boolean isConversations() {
        return true;
    }

    public abstract void signalAccountStateChange();

    public abstract boolean isSynchronizing();

    public abstract void considerSyncBackground(boolean force);
}
