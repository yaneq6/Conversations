package eu.siacs.conversations.ui;

import eu.siacs.conversations.utils.UiCallback;

public interface UiInformableCallback<T> extends UiCallback<T> {
    void inform(String text);
}
