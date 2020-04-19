package net.dankito.mail

import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import com.sun.mail.imap.protocol.BODYSTRUCTURE
import com.sun.mail.util.MailConnectException
import net.dankito.mail.model.*
import net.dankito.utils.IThreadPool
import net.dankito.utils.ThreadPool
import net.dankito.utils.exception.ExceptionHelper
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.mail.*
import javax.mail.event.MessageChangedEvent
import javax.mail.event.MessageChangedListener
import javax.mail.event.MessageCountEvent
import javax.mail.event.MessageCountListener
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlin.concurrent.thread


open class EmailFetcher @JvmOverloads constructor(protected val threadPool: IThreadPool = ThreadPool()) {

    companion object {
        private const val ProtocolPropertiesKey = "mail.store.protocol"

        private val log = LoggerFactory.getLogger(EmailFetcher::class.java)
    }


    protected val messageListeners = ConcurrentHashMap<MessageChangeWatch, Pair<MessageCountListener, MessageChangedListener>>()

    protected val exceptionHelper = ExceptionHelper()


    open fun checkAreCredentialsCorrectAsync(account: MailAccount, callback: (CheckCredentialsResult) -> Unit) {
        threadPool.runAsync {
            callback(checkAreCredentialsCorrect(account))
        }
    }

    open fun checkAreCredentialsCorrect(account: MailAccount): CheckCredentialsResult {
        val connectResult = connect(account, false)

        connectResult.store?.close()

        if (connectResult.successful) {
            return CheckCredentialsResult.Ok
        }

        return mapConnectResultError(connectResult)
    }

    protected open fun connect(account: MailAccount, showDebugOutputOnConsole: Boolean): ConnectResult {
        val props = createPropertiesFromAccount(account)

        val session = Session.getDefaultInstance(props, null)
        session.debug = showDebugOutputOnConsole

        val store = session.getStore(props.getProperty(ProtocolPropertiesKey))

        try {
            store.connect(account.imapServerAddress, account.username, account.password)
        } catch (e: Exception) {
            val errorMessage = "Could not connect to ${account.imapServerAddress}:${account.imapServerPort} for username ${account.username}"
            log.error(errorMessage, e)
            return ConnectResult(Exception(errorMessage, e))
        }

        return ConnectResult(store)
    }

    protected open fun mapConnectResultError(connectResult: ConnectResult): CheckCredentialsResult {
        if (connectResult.error != null) {
            val innerException = exceptionHelper.getInnerException(connectResult.error, 1)

            if (innerException is AuthenticationFailedException) {
                return CheckCredentialsResult.WrongUsername
            }
            else if (innerException is MailConnectException) {
                val innerInnerException = exceptionHelper.getInnerException(innerException, 1)

                if (innerInnerException is UnknownHostException) {
                    return CheckCredentialsResult.InvalidImapServerAddress
                }
                else if (innerInnerException is ConnectException) {
                    return CheckCredentialsResult.InvalidImapServerPort
                }
            }
            else if (innerException is MessagingException) { // MessagingException is derived from MailConnectException, so place after MailConnectException
                return CheckCredentialsResult.WrongPassword
            }
        }

        return CheckCredentialsResult.UnknownError // fallback for cases i am not aware of
    }


    open fun getMailFoldersAsync(account: MailAccount, callback: (GetMailFoldersResult) -> Unit) {
        threadPool.runAsync {
            callback(getMailFolders(account))
        }
    }

    open fun getMailFolders(account: MailAccount): GetMailFoldersResult {
        val connectResult = connect(account, false)

        connectResult.store?.let { store ->
            return GetMailFoldersResult(true, mapFoldersRecursively(store.defaultFolder))
        }

        return GetMailFoldersResult(false, listOf(), connectResult.error)
    }

    protected open fun mapFoldersRecursively(folder: Folder?): List<MailFolder> {
        folder?.let {
            val subFolders = folder.list().toList()

            return subFolders.map {
                MailFolder(it.name, it.messageCount, mapFoldersRecursively(it))
            }
        }

        return listOf()
    }


    protected open fun connectAndOpenFolder(account: MailAccount, folder: String, showDebugOutputOnConsole: Boolean): ConnectAndOpenFolderResult {
        try {
            val connectResult = connect(account, showDebugOutputOnConsole)

            if (connectResult.error != null) {
                return ConnectAndOpenFolderResult(connectResult.error, ConnectAndOpenFolderResultErrorType.CouldNotConnect)
            }
            else if (connectResult.store == null) { // should actually never be the case when error == null
                return ConnectAndOpenFolderResult(Exception("Could not open store"), ConnectAndOpenFolderResultErrorType.CouldNotOpenStore)
            }

            val store = connectResult.store
            val defaultFolder = store.defaultFolder
            val folders = defaultFolder.list()

            val folder = folders.firstOrNull { it.name.contains(folder, true) } // TODO: in this way only top level folders are supported

            if (folder == null) {
                val errorMessage = "Could not find inbox in IMAP folders ${folders.map { it.name }}"
                log.error(errorMessage)
                return ConnectAndOpenFolderResult(Exception(errorMessage), ConnectAndOpenFolderResultErrorType.FolderDoesNotExist)
            }

            if (folder.isOpen == false) {
                folder.open(Folder.READ_ONLY)
            }

            return ConnectAndOpenFolderResult(store, folder)
        } catch (e: Exception) {
            log.error("Could not open folder $folder", e)
            return ConnectAndOpenFolderResult(e, ConnectAndOpenFolderResultErrorType.ExceptionOccurred)
        }
    }


    open fun fetchMailsAsync(options: FetchEmailOptions, callback: (FetchEmailsResult) -> Unit) {
        threadPool.runAsync {
            try {
                fetchMails(options, callback)
            } catch (e: Exception) {
                log.error("Could not fetch mails", e)
                callback(FetchEmailsResult(Exception("Could not fetch mails", e)))
            }
        }
    }

    open fun fetchMails(options: FetchEmailOptions, callback: (FetchEmailsResult) -> Unit) {
        val connectResult = connectAndOpenFolder(options.account, options.folder, options.showDebugOutputOnConsole)

        if (connectResult.error != null) {
            callback(FetchEmailsResult(connectResult.error))
        }
        else if (connectResult.store == null || connectResult.folder == null) {
            callback(FetchEmailsResult(Exception("Unknown error"))) // should never come to this
        }

        val folder = connectResult.folder!!

        var mails = retrieveMails(folder, options, callback)

        try {
            folder.close()

            connectResult.store?.close()
        } catch (e: Exception) {
            log.error("Could not close inbox or store", e)
        }

        if (mails.mapNotNull { it.messageId }.isNotEmpty()) { // messageIds have been retrieved
            mails = mails.sortedBy { it.messageId } // sort by message ids
        }

        callback(FetchEmailsResult(true, mails))
    }


    protected open fun createPropertiesFromAccount(account: MailAccount): Properties {
        val props = System.getProperties()

        props.setProperty(ProtocolPropertiesKey, "imaps") // TODO: make generic
        props.setProperty("mail.imaps.host", account.imapServerAddress)
        props.setProperty("mail.imaps.port", account.imapServerPort.toString())
        props.setProperty("mail.imaps.connectiontimeout", "5000")
        props.setProperty("mail.imaps.timeout", "5000")

        return props
    }


    protected open fun retrieveMails(folder: Folder, options: FetchEmailOptions,
                                     callback: (FetchEmailsResult) -> Unit): List<Email> {

        val countMessages = folder.messageCount

        if (options.retrieveAllMessagesFromThisMessageIdOn != null) {
            return retrieveAllMessagesFromThisMessageIdOn(folder, options, options.retrieveAllMessagesFromThisMessageIdOn, callback)
        }
        else if (options.retrieveOnlyMessagesWithTheseIds.isNullOrEmpty() == false) {
            return retrieveMessagesWithIds(folder, options, options.retrieveOnlyMessagesWithTheseIds, callback)
        }
        else if (options.retrieveMailsInChunks) {
            return retrieveMailsChunked(folder, options, countMessages, callback)
        }
        else {
            return retrieveMails(folder, options, 1, countMessages) // message numbers start at one, not zero
        }
    }

    protected open fun retrieveAllMessagesFromThisMessageIdOn(folder: Folder, options: FetchEmailOptions, retrieveAllMessagesFromThisMessageIdOn: Long,
                                               callback: (FetchEmailsResult) -> Unit): List<Email> {

        (folder as? IMAPFolder)?.let { imapFolder ->
            val messages = imapFolder.getMessagesByUID(retrieveAllMessagesFromThisMessageIdOn, UIDFolder.MAXUID)

            return mapEmailsAccordingToOptions(folder, options, messages.toList(), callback)
        }

        return listOf()
    }

    protected open fun retrieveMessagesWithIds(folder: Folder, options: FetchEmailOptions, retrieveOnlyMessagesWithTheseIds: List<Long>,
                                               callback: (FetchEmailsResult) -> Unit): List<Email> {

        (folder as? IMAPFolder)?.let { imapFolder ->
            val messages = imapFolder.getMessagesByUID(retrieveOnlyMessagesWithTheseIds.toLongArray())

            return mapEmailsAccordingToOptions(folder, options, messages.toList(), callback)
        }

        return listOf()
    }

    protected open fun retrieveMailsChunked(folder: Folder, options: FetchEmailOptions, countMessages: Int,
                                            callback: (FetchEmailsResult) -> Unit): MutableList<Email> {

        var messageNumberStart = 1 // message numbers start at 1
        var messageNumberEnd = messageNumberStart + options.chunkSize - 1
        if (messageNumberEnd > countMessages) {
            messageNumberEnd = countMessages
        }

        val mails = mutableListOf<Email>()

        while (messageNumberEnd <= countMessages) { // end is inclusive
            val retrievedChunk = retrieveMails(folder, options, messageNumberStart, messageNumberEnd)
            mails.addAll(retrievedChunk)

            callback(FetchEmailsResult(false, mails, retrievedChunk))

            val lastMessageNumberEnd = messageNumberEnd
            messageNumberStart = messageNumberEnd + 1
            messageNumberEnd += options.chunkSize
            if (messageNumberEnd > countMessages) {
                if (lastMessageNumberEnd < countMessages) {
                    messageNumberEnd = countMessages
                    messageNumberStart = lastMessageNumberEnd + 1
                }
            }
        }

        return mails
    }

    protected open fun retrieveMails(folder: Folder, options: FetchEmailOptions, messageNumberStart: Int,
                                     messageNumberEnd: Int): List<Email> {

        val messages = folder.getMessages(messageNumberStart, messageNumberEnd)
        log.debug("Retrieved ${messages.size} Messages")

        prefetchRequiredData(folder, options, messages)

        return mapEmails(folder, options, messages.toList())
    }

    protected open fun mapEmailsAccordingToOptions(folder: Folder, options: FetchEmailOptions, messages: List<Message>, callback: (FetchEmailsResult) -> Unit): List<Email> {
        val messagesSorted = messages.sortedBy { it.messageNumber }

        prefetchRequiredData(folder, options, messagesSorted.toTypedArray())

        if (options.retrieveMailsInChunks == false) {
            return mapEmails(folder, options, messagesSorted)
        }
        else {
            return mapEmailsChunked(messagesSorted, options, folder, callback)
        }
    }

    protected open fun prefetchRequiredData(folder: Folder, options: FetchEmailOptions, messages: Array<Message>) {
        val fetchProfile = FetchProfile()

        fetchProfile.add(FetchProfile.Item.ENVELOPE)
        fetchProfile.add(FetchProfile.Item.SIZE)

        if (options.retrievePlainTextBodies || options.retrieveHtmlBodies) {
            fetchProfile.add(FetchProfile.Item.CONTENT_INFO)
        }

        folder.fetch(messages, fetchProfile) // Load the profile of the messages in 1 fetch.
    }

    protected open fun mapEmails(folder: Folder, options: FetchEmailOptions, messages: List<Message>): List<Email> {
        return messages.map { message ->
            mapEmail(folder, options, message)
        }
    }

    protected open fun mapEmailsChunked(messages: List<Message>, options: FetchEmailOptions, folder: Folder, callback: (FetchEmailsResult) -> Unit): MutableList<Email> {
        val countChunks = Math.max(1, Math.round(messages.size / options.chunkSize.toFloat()))
        val allMails = mutableListOf<Email>()

        for (chunkIndex in 0 until countChunks) {
            val chunk = IntRange(0, options.chunkSize - 1).mapNotNull { indexInChunk ->
                val nextMessageIndex = chunkIndex * options.chunkSize + indexInChunk
                if (nextMessageIndex < messages.size) {
                    try {
                        return@mapNotNull mapEmail(folder, options, messages[nextMessageIndex])
                    } catch (e: Exception) {
                        log.error("Could not map message ${messages[nextMessageIndex]} to Email", e)
                    }
                }

                null
            }

            allMails.addAll(chunk)

            callback(FetchEmailsResult(false, allMails, chunk))
        }

        return allMails
    }

    protected open fun mapEmail(folder: Folder, options: FetchEmailOptions, message: Message): Email {
        val recipients = message.allRecipients?.map { mapSenderOrRecipient(it) } ?: listOf()
        val mail = Email(mapSenderOrRecipient(message.from.firstOrNull()), recipients, message.subject ?: "", message.receivedDate, message.sentDate)

        (message as? IMAPMessage)?.let { imapMessage ->
            mail.size = imapMessage.sizeLong
        }
        ?: (message as? MimeMessage)?.let { mimeMessage ->
            mail.size = mimeMessage.size.toLong()
        }

        setEmailBody(folder, options, message, mail)

        return mail
    }

    protected open fun mapSenderOrRecipient(senderOrRecipient: Address?): String {
        return senderOrRecipient?.toString() ?: ""
    }

    protected open fun setEmailBody(folder: Folder, options: FetchEmailOptions, message: Message, mail: Email) {
        (message as? IMAPMessage)?.let { imapMessage ->
            if (options.retrieveMessageIds) {
                mail.messageId = (folder as? IMAPFolder)?.getUID(message) // this again needs a network request
            }

            if (options.retrieveAttachmentInfos && options.retrievePlainTextBodies == false
                && options.retrieveHtmlBodies == false) { // we cannot load bodies but body infos from BODYSTRUCTURE

                setBodyInfoAndAttachmentInfoFromBodyStructure(imapMessage, mail)
            }
        }

        if (options.retrievePlainTextBodies || options.retrieveHtmlBodies || options.downloadAttachments) { // bodies can be loaded from message or multipart content
            setBodyAndAttachmentsFromMessageContent(options, message, mail)
        }
    }

    protected open fun setBodyInfoAndAttachmentInfoFromBodyStructure(message: IMAPMessage, mail: Email) {
        val contentTypeWithAdditionalInfo = message.contentType // to load body structure
        mail.contentType = extractContentType(contentTypeWithAdditionalInfo)

        try {
            val bodyStructureField = IMAPMessage::class.java.getDeclaredField("bs")
            bodyStructureField.isAccessible = true

            (bodyStructureField.get(message) as? BODYSTRUCTURE)?.let { bodyStructure ->
                setBodyInfoAndAttachmentInfoFromBodyStructure(mail, bodyStructure)
            }
        } catch (e: Exception) {
            log.error("Could not load message's body structure", e)
        }
    }

    protected open fun setBodyInfoAndAttachmentInfoFromBodyStructure(mail: Email, bodyStructure: BODYSTRUCTURE) {
        when {
            bodyStructure.bodies?.isNotEmpty() == true -> bodyStructure.bodies.forEach { setBodyInfoAndAttachmentInfoFromBodyStructure(mail, it) }
            bodyStructure.disposition == "attachment" -> addAttachmentInfoFromBodyStructure(mail, bodyStructure)
            bodyStructure.type == "text" -> setBodyInfoFromBodyStructure(mail, bodyStructure)
        }
    }

    protected open fun setBodyInfoFromBodyStructure(mail: Email, bodyStructure: BODYSTRUCTURE) {
        when (bodyStructure.subtype) {
            "plain" -> mail.plainTextBodyInfo = EmailBodyInfo(bodyStructure.size, bodyStructure.lines)
            "html" -> mail.htmlBodyInfo = EmailBodyInfo(bodyStructure.size, bodyStructure.lines)
        }
    }

    protected open fun addAttachmentInfoFromBodyStructure(mail: Email, bodyStructure: BODYSTRUCTURE) {
        val name = bodyStructure.dParams?.get("filename") ?: bodyStructure.cParams?.get("name") ?: ""
        mail.addAttachmentInfo(AttachmentInfo(name, bodyStructure.size, bodyStructure.type + "/" + bodyStructure.subtype))
    }


    protected open fun setBodyAndAttachmentsFromMessageContent(options: FetchEmailOptions, message: Message, mail: Email) {
        mail.contentType = extractContentType(message.contentType)

        if (message.contentType.contains("text/", true)) {
            setTextBody(options, mail, message.contentType, message.content.toString())
        }
        else if (message.contentType.contains("multipart/", true)) {
            (message.content as? MimeMultipart)?.let { multipartContent ->
                setBodyAndAttachmentsFromMultiPartContent(options, mail, multipartContent)
            }
        }
        else {
            log.info("Cannot map message content type ${message.contentType}")
        }
    }

    protected open fun setBodyAndAttachmentsFromMultiPartContent(options: FetchEmailOptions, mail: Email, multipart: MimeMultipart) {
        for (i in 0..multipart.count - 1) {
            val bodyPart = multipart.getBodyPart(i)

            val isAttachment = Part.ATTACHMENT.equals(bodyPart.disposition, true)

            if (isAttachment == false && bodyPart.contentType.contains("text/", true)) {
                if (options.retrievePlainTextBodies || options.retrieveHtmlBodies) {
                    setTextBody(options, mail, bodyPart.contentType, bodyPart.content.toString())
                }
            }
            else if (bodyPart.contentType.contains("multipart", true)) {
                (bodyPart.content as? MimeMultipart)?.let { partMultipartContent ->
                    setBodyAndAttachmentsFromMultiPartContent(options, mail, partMultipartContent)
                }
            }
            else if (isAttachment && (options.retrieveAttachmentInfos || options.downloadAttachments)) {
                downloadAttachmentOrGetAttachmentInfo(bodyPart, i, options, mail)
            }
        }
    }

    protected open fun downloadAttachmentOrGetAttachmentInfo(bodyPart: BodyPart, index: Int, options: FetchEmailOptions, mail: Email) {
        val fileName = bodyPart.fileName ?: "Attachment_${index + 1}"

        val contentType = extractContentType(bodyPart.contentType)

        if (options.retrieveAttachmentInfos) {
            mail.addAttachmentInfo(AttachmentInfo(fileName, bodyPart.size, contentType))
        }
        if (options.downloadAttachments) {
            val content = bodyPart.inputStream.buffered().readBytes()

            mail.addAttachment(Attachment(fileName, bodyPart.size, contentType, content))
        }

        if (bodyPart.fileName.isNullOrEmpty()) {
            log.info("part.fileName is null or empty for mail $mail")
        }
    }

    protected open fun setTextBody(options: FetchEmailOptions, mail: Email, contentType: String, content: String) {
        if (contentType.contains("text/plain", true)) {
            if (options.retrievePlainTextBodies) {
                mail.plainTextBody = content

                if (mail.contentType?.startsWith("multipart") == true) {
                    mail.contentType = "text/plain"
                }
            }
        }
        else if (contentType.contains("text/html", true)) {
            if (options.retrieveHtmlBodies) {
                mail.htmlBody = content
                mail.contentType = "text/html"
            }
        }
    }


    protected open fun extractContentType(contentTypeWithAdditionalInfo: String): String {
        var contentType = contentTypeWithAdditionalInfo

        val indexOfSemicolon = contentType.indexOf(';')
        if (indexOfSemicolon > 0) { // throw away e.g. 'name=' and 'boundary='
            contentType = contentType.substring(0, indexOfSemicolon) // TODO: try to keep charset?
        }

        return contentType.toLowerCase()
    }


    /**
     * Be aware for deleted messages [Email] object in call to listener is null as we don't get any information about
     * the deleted mail, not even its messageId.
     */
    open fun addMessageListener(options: MessageChangedListenerOptions, listener: net.dankito.mail.model.MessageChangedListener) {
        addMessageListener(options) { type, mail ->
            listener.messageChanged(type, mail)
        }
    }

    /**
     * Be aware for deleted messages [Email] object in call to listener is null as we don't get any information about
     * the deleted mail, not even its messageId.
     */
    open fun addMessageListener(options: MessageChangedListenerOptions, listener: (MessageChangeType, Email?) -> Unit): IMessageChangeWatch? {
        val connectResult = connectAndOpenFolder(options.account, options.folder, options.showDebugOutputOnConsole)

        if (connectResult.error != null) {
            return null
        }

        connectResult.folder?.let { folder ->
            return addMessageListener(options, listener, folder)
        }

        return null
    }

    private fun addMessageListener(options: MessageChangedListenerOptions, listener: (MessageChangeType, Email?) -> Unit, folder: Folder): MessageChangeWatch {
        val fetchEmailOptions = FetchEmailOptions(
            options.account, retrieveMessageIds = true, retrievePlainTextBodies = true,
            retrieveHtmlBodies = true, downloadAttachments = true
        )

        val messageCountListener = object : MessageCountListener {

            override fun messagesAdded(event: MessageCountEvent?) {
                event?.let {
                    event.messages.forEach { message ->
                        listener(MessageChangeType.Added, mapEmail(folder, fetchEmailOptions, message))
                    }
                }
            }

            override fun messagesRemoved(event: MessageCountEvent?) {
                event?.let {
                    event.messages.forEach { message ->
                        // for deleted mails no information can be retrieved
                        listener(MessageChangeType.Deleted, null)
                    }
                }
            }

        }
        folder.addMessageCountListener(messageCountListener)


        val messageChangedListener = MessageChangedListener { event ->
            if (event.messageChangeType != MessageChangedEvent.FLAGS_CHANGED) {
                listener(MessageChangeType.Modified, mapEmail(folder, fetchEmailOptions, event.message))
            }
        }
        folder.addMessageChangedListener(messageChangedListener)


        val watch = MessageChangeWatch(options.account, folder)
        messageListeners.put(watch, Pair(messageCountListener, messageChangedListener))

        keepMessageChangedListenersAlive(folder, watch)

        return watch
    }

    private fun keepMessageChangedListenersAlive(folder: Folder, watch: IMessageChangeWatch) {
        (folder as? IMAPFolder)?.let { imapFolder ->
            thread {
                try {
                    // see https://github.com/javaee/javamail/blob/master/demo/src/main/java/monitor.java
                    while (messageListeners.containsKey(watch)) {
                        imapFolder.idle() // we periodically need to call idle() to keep listeners alive (for POP periodically folder.getMessageCount() has to be called)
                    }
                } catch (e: Exception) {
                    log.error("Error occurred while watching folder $folder", e)
                }
            }
        }
    }


    open fun removeMessageListener(watch: IMessageChangeWatch) {
        (watch as? MessageChangeWatch)?.let {
            messageListeners.remove(watch)?.let { listeners ->
                watch.folder.removeMessageCountListener(listeners.first)

                watch.folder.removeMessageChangedListener(listeners.second)

                watch.folder.close()
                watch.folder.store.close()
            }
        }
    }

}