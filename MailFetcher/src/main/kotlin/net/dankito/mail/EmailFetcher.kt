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
import javax.mail.*
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart


open class EmailFetcher @JvmOverloads constructor(protected val threadPool: IThreadPool = ThreadPool()) {

    companion object {
        private const val ProtocolPropertiesKey = "mail.store.protocol"

        private val log = LoggerFactory.getLogger(EmailFetcher::class.java)
    }


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
            store.connect(account.host, account.username, account.password)
        } catch (e: Exception) {
            val errorMessage = "Could not connect to ${account.host}:${account.port} for username ${account.username}"
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
                    return CheckCredentialsResult.WrongHostUrl
                }
                else if (innerInnerException is ConnectException) {
                    return CheckCredentialsResult.WrongPort
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

    private fun mapFoldersRecursively(folder: Folder?): List<MailFolder> {
        folder?.let {
            val subFolders = folder.list().toList()

            return subFolders.map {
                MailFolder(it.name, it.messageCount, mapFoldersRecursively(it))
            }
        }

        return listOf()
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
        val connectResult = connect(options.account, options.showDebugOutputOnConsole)

        if (connectResult.error != null) {
            callback(FetchEmailsResult(connectResult.error))
        }
        else if (connectResult.store != null) {
            fetchMails(options, connectResult.store, callback)
        }
        else {
            callback(FetchEmailsResult(Exception("Unknown error"))) // should never come to this
        }
    }

    protected open fun fetchMails(options: FetchEmailOptions, store: Store, callback: (FetchEmailsResult) -> Unit) {
        val defaultFolder = store.defaultFolder
        val folders = defaultFolder.list()

        val inbox = folders.firstOrNull { it.name.contains("inbox", true) }

        if (inbox == null) {
            val errorMessage = "Could not find inbox in IMAP folders ${folders.map { it.name }}"
            log.error(errorMessage)
            callback(FetchEmailsResult(Exception(errorMessage)))
            return
        }

        if (inbox.isOpen == false) {
            inbox.open(Folder.READ_ONLY)
        }

        val mails = retrieveMails(inbox, options, callback)

        try {
            inbox.close()

            store.close()
        } catch (e: Exception) {
            log.error("Could not close inbox or store", e)
        }

        callback(FetchEmailsResult(true, mails))
    }


    protected open fun createPropertiesFromAccount(account: MailAccount): Properties {
        val props = System.getProperties()

        props.setProperty(ProtocolPropertiesKey, "imaps") // TODO: make generic
        props.setProperty("mail.imaps.host", account.host)
        props.setProperty("mail.imaps.port", account.port.toString())
        props.setProperty("mail.imaps.connectiontimeout", "5000")
        props.setProperty("mail.imaps.timeout", "5000")

        return props
    }


    protected open fun retrieveMails(folder: Folder, options: FetchEmailOptions,
                                     callback: (FetchEmailsResult) -> Unit): List<Email> {

        val countMessages = folder.messageCount

        if (options.retrieveOnlyMessagesWithTheseIds.isNullOrEmpty() == false) {
            return retrieveMessagesWithIds(folder, options, options.retrieveOnlyMessagesWithTheseIds, callback)
        }
        else if (options.retrieveMailsInChunks) {
            return retrieveMailsChunked(folder, options, countMessages, callback)
        }
        else {
            return retrieveMails(folder, options, 1, countMessages) // message numbers start at one, not zero
        }
    }

    protected open fun retrieveMessagesWithIds(folder: Folder, options: FetchEmailOptions, retrieveOnlyMessagesWithTheseIds: List<Long>,
                                               callback: (FetchEmailsResult) -> Unit): List<Email> {

        (folder as? IMAPFolder)?.let { imapFolder ->
            // TODO: implement options.chunkSize
            val messages = imapFolder.getMessagesByUID(retrieveOnlyMessagesWithTheseIds.toLongArray())

            return mapEmails(folder, options, messages)
        }

        return listOf()
    }

    protected open fun retrieveMailsChunked(folder: Folder, options: FetchEmailOptions, countMessages: Int,
                                            callback: (FetchEmailsResult) -> Unit): MutableList<Email> {

        var messageNumberEnd = countMessages
        var messageNumberStart = folder.messageCount - options.chunkSize + 1 // + 1 as end is inclusive
        if (messageNumberStart < 1) {
            messageNumberStart = 1
        }

        val mails = mutableListOf<Email>()

        while (messageNumberStart > 0) {
            val retrievedChunk = retrieveMails(folder, options, messageNumberStart, messageNumberEnd)
            mails.addAll(retrievedChunk)

            callback(FetchEmailsResult(false, mails, retrievedChunk))

            val lastMessageNumberStart = messageNumberStart
            messageNumberEnd = messageNumberStart - 1
            messageNumberStart -= options.chunkSize
            if (messageNumberStart < 1) {
                if (lastMessageNumberStart > 1) {
                    messageNumberStart = 1
                    messageNumberEnd = lastMessageNumberStart - 1
                }
            }
        }

        return mails
    }

    protected open fun retrieveMails(folder: Folder, options: FetchEmailOptions, messageNumberStart: Int,
                                     messageNumberEnd: Int): List<Email> {

        val messages = folder.getMessages(messageNumberStart, messageNumberEnd)
        log.debug("Retrieved ${messages.size} Messages")

        return mapEmails(folder, options, messages)
    }

    protected open fun mapEmails(folder: Folder, options: FetchEmailOptions, messages: Array<Message>): List<Email> {
        return messages.map { message ->
            mapEmail(folder, options, message)
        }
    }

    protected open fun mapEmail(folder: Folder, options: FetchEmailOptions, message: Message): Email {
        val mail = Email(message.from.joinToString { it.toString() }, message.subject ?: "", message.receivedDate, message.sentDate)

        (message as? IMAPMessage)?.let { imapMessage ->
            mail.size = imapMessage.sizeLong
        }
        ?: (message as? MimeMessage)?.let { mimeMessage ->
            mail.size = mimeMessage.size.toLong()
        }

        setEmailBody(folder, options, message, mail)

        return mail
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
        val contentType = message.contentType // to load body structure

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
            else if (options.retrieveAttachmentInfos || options.downloadAttachments) {
                downloadAttachmentOrGetAttachmentInfo(bodyPart, i, options, mail)
            }
        }
    }

    protected open fun downloadAttachmentOrGetAttachmentInfo(bodyPart: BodyPart, index: Int, options: FetchEmailOptions, mail: Email) {
        val fileName = bodyPart.fileName ?: "Attachment_${index + 1}"

        var mimeType = bodyPart.contentType
        val indexOfSemicolon = mimeType.indexOf(';')
        if (indexOfSemicolon > 0) {
            mimeType = mimeType.substring(0, indexOfSemicolon) // TODO: try to keep charset?
        }

        if (options.retrieveAttachmentInfos) {
            mail.addAttachmentInfo(AttachmentInfo(fileName, bodyPart.size, mimeType))
        }
        if (options.downloadAttachments) {
            val content = bodyPart.inputStream.buffered().readBytes()

            mail.addAttachment(Attachment(fileName, bodyPart.size, mimeType, content))
        }

        if (bodyPart.fileName.isNullOrEmpty()) {
            log.info("part.fileName is null or empty for mail $mail")
        }
    }

    protected open fun setTextBody(options: FetchEmailOptions, mail: Email, contentType: String, content: String) {
        if (contentType.contains("text/plain", true)) {
            if (options.retrievePlainTextBodies) {
                mail.plainTextBody = content
            }
        }
        else if (contentType.contains("text/html", true)) {
            if (options.retrieveHtmlBodies) {
                mail.htmlBody = content
            }
        }
    }

}