package net.dankito.mail.model


interface MessageChangedListener {

    /**
     * Be aware when messages get deleted we don't get any information about the deleted mails, not even its messageId.
     * We are trying our best to find out which messages got deleted, but we cannot guarantee that this works in all cases.
     */
    fun messageChanged(type: MessageChangeType, message: Email)

}