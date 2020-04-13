package net.dankito.accounting.service.email

import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import com.sun.mail.imap.protocol.BODYSTRUCTURE
import net.dankito.accounting.data.model.email.*
import net.dankito.utils.IThreadPool
import org.slf4j.LoggerFactory
import java.util.*
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.MimeMultipart


open class EmailFetcher(protected val threadPool: IThreadPool) {

    companion object {
        private const val ProtocolPropertiesKey = "mail.store.protocol"

        private val log = LoggerFactory.getLogger(EmailFetcher::class.java)
    }


    fun fetchEmailsAsync(options: FetchEmailOptions, callback: (FetchEmailsResult) -> Unit) {
        threadPool.runAsync {
            try {
                fetchEmails(options, callback)
            } catch (e: Exception) {
                log.error("Could not fetch mails", e)
                callback(FetchEmailsResult(true, listOf(), Exception("Could not fetch mails", e)))
            }
        }
    }

    fun fetchEmails(options: FetchEmailOptions, callback: (FetchEmailsResult) -> Unit) {
        val account = options.account

        val props = createPropertiesFromAccount(account)

        val session = Session.getDefaultInstance(props, null)
        session.debug = options.showDebugOutputOnConsole

        val store = session.getStore(props.getProperty(ProtocolPropertiesKey))
        try {
            store.connect(account.host, account.username, account.password)
        } catch (e: Exception) {
            val errorMessage = "Could not connect to ${account.host}:${account.port} for username ${account.username}"
            log.error(errorMessage, e)
            callback(FetchEmailsResult(true, listOf(), Exception(errorMessage, e)))
            return
        }

        val defaultFolder = store.defaultFolder
        val folders = defaultFolder.list()

        val inbox = folders.firstOrNull { it.name.contains("inbox", true) }

        if (inbox == null) {
            val errorMessage = "Could not find inbox in IMAP folders ${folders.map { it.name }}"
            log.error(errorMessage)
            callback(FetchEmailsResult(true, listOf(), Exception(errorMessage)))
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


    protected open fun createPropertiesFromAccount(account: EmailAccount): Properties {
        val props = System.getProperties()

        props.setProperty(ProtocolPropertiesKey, "imaps") // TODO: make generic
        props.setProperty("mail.imaps.host", account.host)
        props.setProperty("mail.imaps.port", account.port.toString())
        props.setProperty("mail.imaps.connectiontimeout", "5000")
        props.setProperty("mail.imaps.timeout", "5000")

        return props
    }


    protected open fun retrieveMails(inbox: Folder, options: FetchEmailOptions,
                                     callback: (FetchEmailsResult) -> Unit): List<Email> {

        val countMessages = inbox.messageCount

        if (options.retrieveMailsInChunks) {
            return retrieveMailsChunked(inbox, options, countMessages, callback)
        }
        else {
            return retrieveMails(inbox, options, 1, countMessages) // message numbers start at one, not zero
        }
    }

    protected open fun retrieveMailsChunked(inbox: Folder, options: FetchEmailOptions, countMessages: Int,
                                     callback: (FetchEmailsResult) -> Unit): MutableList<Email> {

        var messageNumberEnd = countMessages
        var messageNumberStart = inbox.messageCount - options.chunkSize + 1 // + 1 as end is inclusive
        if (messageNumberStart < 1) {
            messageNumberStart = 1
        }

        val mails = mutableListOf<Email>()

        while (messageNumberStart > 0) {
            val retrievedChunk = retrieveMails(inbox, options, messageNumberStart, messageNumberEnd)
            mails.addAll(retrievedChunk)

            if (messageNumberStart > 1) {
                callback(FetchEmailsResult(false, mails))
            }

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
        log.info("Retrieved ${messages.size} Messages")

        return messages.map { message ->
            mapEmail(folder, options, message)
        }
    }

    protected open fun mapEmail(folder: Folder, options: FetchEmailOptions, message: Message): Email {
        val mail = Email(message.from.joinToString { it.toString() }, message.subject ?: "", message.receivedDate)

        setEmailBody(folder, options, message, mail)

        return mail
    }

    protected open fun setEmailBody(folder: Folder, options: FetchEmailOptions, message: Message, mail: Email) {
        (message as? IMAPMessage)?.let { imapMessage ->
            if (options.retrieveMessageIds) {
                mail.messageId = (folder as? IMAPFolder)?.getUID(message) // this again needs a network request
            }

            if (options.retrieveAttachmentNames && options.retrievePlainTextBodies == false
                && options.retrieveHtmlBodies == false) { // we cannot load bodies but body infos from BODYSTRUCTURE

                setBodyInfoAndAttachmentsFromBodyStructure(imapMessage, mail)
            }
        }

        if (options.retrievePlainTextBodies || options.retrieveHtmlBodies) { // bodies can be loaded from message or multipart content
            setBodyAndAttachmentsFromMessageContent(options, message, mail)
        }
    }

    protected open fun setBodyInfoAndAttachmentsFromBodyStructure(message: IMAPMessage, mail: Email) {
        val contentType = message.contentType // to load body structure

        try {
            val bodyStructureField = IMAPMessage::class.java.getDeclaredField("bs")
            bodyStructureField.isAccessible = true

            (bodyStructureField.get(message) as? BODYSTRUCTURE)?.let { bodyStructure ->
                setBodyInfoAndAttachmentsFromBodyStructure(mail, bodyStructure)
            }
        } catch (e: Exception) {
            log.error("Could not load message's body structure", e)
        }
    }

    protected open fun setBodyInfoAndAttachmentsFromBodyStructure(mail: Email, bodyStructure: BODYSTRUCTURE) {
        when {
            bodyStructure.bodies?.isNotEmpty() == true -> bodyStructure.bodies.forEach { setBodyInfoAndAttachmentsFromBodyStructure(mail, it) }
            bodyStructure.disposition == "attachment" -> addAttachmentsFromBodyStructure(mail, bodyStructure)
            bodyStructure.type == "text" -> setBodyInfoFromBodyStructure(mail, bodyStructure)
        }
    }

    protected open fun setBodyInfoFromBodyStructure(mail: Email, bodyStructure: BODYSTRUCTURE) {
        when (bodyStructure.subtype) {
            "plain" -> mail.plainTextBodyInfo = EmailBodyInfo(bodyStructure.size, bodyStructure.lines)
            "html" -> mail.htmlBodyInfo = EmailBodyInfo(bodyStructure.size, bodyStructure.lines)
        }
    }

    protected open fun addAttachmentsFromBodyStructure(mail: Email, bodyStructure: BODYSTRUCTURE) {
        mail.addAttachment(Attachment(bodyStructure.dParams["filename"] ?: "", bodyStructure.size, bodyStructure.type + "/" + bodyStructure.subtype))
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
            val part = multipart.getBodyPart(i)

            if (part.contentType.contains("text/", true)) {
                setTextBody(options, mail, part.contentType, part.content.toString())
            }
            else if (part.contentType.contains("multipart", true)) {
                (part.content as? MimeMultipart)?.let { partMultipartContent ->
                    setBodyAndAttachmentsFromMultiPartContent(options, mail, partMultipartContent)
                }
            }
            else if (options.retrieveAttachmentNames) {
                var mimeType = part.contentType
                val indexOfSemicolon = mimeType.indexOf(';')
                if (indexOfSemicolon > 0) {
                    mimeType = mimeType.substring(0, indexOfSemicolon)
                }

                mail.addAttachment(Attachment(part.fileName ?: "", part.size, mimeType))

                if (part.fileName.isNullOrEmpty()) {
                    log.info("part.fileName is null or empty for mail $mail")
                }
            }
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