package net.dankito.mail.model


interface MessageChangedListener {

    /**
     * Be aware for deleted messages [Email] object is null as we don't get any information about
     * the deleted mail, not even its messageId.
     */
    fun messageChanged(type: MessageChangeType, message: Email?)

}