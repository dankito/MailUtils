package net.dankito.mail.model


class FetchEmailOptions(
    val account: MailAccount,
    val retrieveOnlyMessagesWithTheseIds: List<Long>? = null,
    val retrieveMessageIds: Boolean = false,
    val retrievePlainTextBodies: Boolean = false,
    val retrieveHtmlBodies: Boolean = false,
    val retrieveAttachmentInfos: Boolean = false,
    val downloadAttachments: Boolean = false,
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