package net.dankito.accounting.service.email

import com.sun.mail.imap.protocol.BODYSTRUCTURE
import net.dankito.accounting.data.model.email.Attachment
import net.dankito.accounting.data.model.email.Email
import net.dankito.accounting.data.model.email.EmailAccount
import org.slf4j.LoggerFactory
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.MimeMultipart


open class EmailFetcher {

    companion object {
        private val log = LoggerFactory.getLogger(EmailFetcher::class.java)
    }


    fun fetchEmailsAsync(account: EmailAccount, callback: (List<Email>) -> Unit) {
        val props = System.getProperties()
        props.setProperty("mail.store.protocol", "imaps") // TODO: make generic
        props.setProperty("mail.imaps.host", account.host)
        props.setProperty("mail.imaps.port", account.port.toString())
        props.setProperty("mail.imaps.connectiontimeout", "5000")
        props.setProperty("mail.imaps.timeout", "5000")

        val session = Session.getDefaultInstance(props, null)
        session.debug = true

        val store = session.getStore("imaps")
        store.connect(account.host, account.username, account.password)

//        val defaultFolder = store.defaultFolder
//        val folders = defaultFolder.list()
        val inbox = store.getFolder("inbox")
//        val inboxFolders = inbox.list()

        if (inbox.isOpen == false) {
            inbox.open(Folder.READ_WRITE)
        }

        val messages = inbox.messages.sortedByDescending { it.sentDate }
        log.info("Retrieved ${messages.size} Messages:")

        val mails = messages.map { message ->
            val mail = Email(message.from.joinToString { it.toString() }, message.subject ?: "")

//            (message as? IMAPMessage)?.let { imapMessage ->
//                val id = (inbox as? IMAPFolder)?.getUID(message) // this again needs a network request
//
//                val contentType = message.contentType // to load body structure
//
//                try {
//                    val bodyStructureField = IMAPMessage::class.java.getDeclaredField("bs")
//                    bodyStructureField.isAccessible = true
//
//                    (bodyStructureField.get(imapMessage) as? BODYSTRUCTURE)?.let { bodyStructure ->
//                        setBodyAndAttachmentsFromBodyStructure(mail, bodyStructure)
//                    }
//                } catch (e: Exception) {
//                    log.error("Could not load message's body structure", e)
//                }
//            }

            setBodyAndAttachments(mail, message)

            mail
        }

        inbox.close()

        store.close()

        callback(mails)
    }

    protected open fun setBodyAndAttachmentsFromBodyStructure(mail: Email, bodyStructure: BODYSTRUCTURE) {
        when {
            bodyStructure.bodies?.isNotEmpty() == true -> bodyStructure.bodies.forEach { setBodyAndAttachmentsFromBodyStructure(mail, it) }
            bodyStructure.disposition == "attachment" -> addAttachmentsFromBodyStructure(mail, bodyStructure)
            bodyStructure.type == "text" -> setBodyFromBodyStructure(mail, bodyStructure)
        }
    }

    private fun setBodyFromBodyStructure(mail: Email, bodyStructure: BODYSTRUCTURE) {
        when (bodyStructure.subtype) {
            "plain" -> mail.htmlBody = "" + bodyStructure.size + ", " + bodyStructure.lines + " lines" // TODO: set plain text info
            "html" -> mail.htmlBody = "" + bodyStructure.size + ", " + bodyStructure.lines + " lines" // TODO: set html info
        }
    }

    protected open fun addAttachmentsFromBodyStructure(mail: Email, bodyStructure: BODYSTRUCTURE) {
        mail.addAttachment(Attachment(bodyStructure.dParams["filename"] ?: "", bodyStructure.size, bodyStructure.type + "/" + bodyStructure.subtype))
    }

    protected open fun setBodyAndAttachments(mail: Email, message: Message) {
        if (message.contentType.contains("text/", true)) {
//            setTextBody(mail, message.contentType, message.content.toString())
        }
        else if (message.contentType.contains("multipart/", true)) {
            (message.content as? MimeMultipart)?.let { multipartContent ->
                setBodyAndAttachmentsFromMultiPartContent(mail, multipartContent)
            }
        }
        else {
            println("Cannot map message content type ${message.contentType}")
        }
    }

    protected open fun setBodyAndAttachmentsFromMultiPartContent(mail: Email, multipart: MimeMultipart) {
        for (i in 0..multipart.count - 1) {
            val part = multipart.getBodyPart(i)

            if (part.contentType.contains("text/", true)) {
                setTextBody(mail, part.contentType, part.content.toString())
            }
            else if (part.contentType.contains("multipart", true)) { // can this ever be the case?
                println("Multipart's content type is again multipart: ${part.contentType}")
//                (part.content as? MimeMultipart)?.let { partMultipartContent ->
//                    setBodyAndAttachmentsFromMultiPartContent(mail, partMultipartContent)
//                }
            }
            else {
                println("Cannot map multipart content type ${part.contentType}")
                var mimeType = part.contentType
                val indexOfSemicolon = mimeType.indexOf(';')
                if (indexOfSemicolon > 0) {
                    mimeType = mimeType.substring(0, indexOfSemicolon)
                }

                mail.addAttachment(Attachment(part.fileName ?: "", part.size, mimeType))

                if (part.fileName.isNullOrEmpty()) {
                    println("part.fileName is null or empty for mail $mail")
                }
            }
        }
    }

    protected open fun setTextBody(mail: Email, contentType: String, content: String) {
        if (contentType.contains("text/plain", true)) {
            mail.plainTextBody = content
        }
        else if (contentType.contains("text/html", true)) {
            mail.htmlBody = content
        }
    }

}