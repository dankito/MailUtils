package net.dankito.mail.model


class FetchEmailOptions(
    val account: EmailAccount,
    val retrieveMessageIds: Boolean,
    val retrievePlainTextBodies: Boolean,
    val retrieveHtmlBodies: Boolean,
    val retrieveAttachmentInfos: Boolean,
    val downloadAttachments: Boolean,
    /**
     * If set to a value greater zero after each received [chunkSize] mails a [FetchEmailsResult] will be fired with
     * [FetchEmailsResult.completed] == false
     */
    val chunkSize: Int = -1,
    /**
     * If set to true debug messages get printed to console.
     * Otherwise no messages at all get printed
     */
    val showDebugOutputOnConsole: Boolean = false
) {

    val retrieveMailsInChunks = chunkSize > 0

}