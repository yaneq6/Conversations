package eu.siacs.conversations.services.interfaces;

import java.util.List;

import eu.siacs.conversations.utils.Attachment;

public interface OnMediaLoaded {

    void onMediaLoaded(List<Attachment> attachments);
}
