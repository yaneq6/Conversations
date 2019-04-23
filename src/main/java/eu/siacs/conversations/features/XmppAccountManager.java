package eu.siacs.conversations.features;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;

public interface XmppAccountManager {
    XmppConnectionService getXmppConnectionService();
    void switchToAccount(Account account);
    void switchToAccount(Account account, String fingerprint);
    void switchToAccount(Account account, boolean init, String fingerprint);
}
