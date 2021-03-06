package net.dankito.mail.model

import java.text.DateFormat
import java.util.*


open class Email(val sender: String,
                 val recipients: List<String>,
                 val subject: String,
                 val receivedDate: Date,
                 val sentDate: Date? = null
) {

    protected val attachmentInfosField = mutableListOf<AttachmentInfo>()

    protected val attachmentsField = mutableListOf<Attachment>()


    open var messageId: Long? = null


    open var size: Long? = null

    /**
     * contentType does not loaded in each case as this causes a new request to server.
     *
     * It only gets set if bodies get loaded anyway.
     */
    open var contentType: String? = null


    open var plainTextBodyInfo: EmailBodyInfo? = null

    open var plainTextBody: String? = null

    open var htmlBodyInfo: EmailBodyInfo? = null

    open var htmlBody: String? = null

    /**
     * May be null, e.g. for encrypted messages
     */
    open val body: String?
        get() = plainTextBody ?: htmlBody


    open val attachmentInfos: List<AttachmentInfo>
        get() = ArrayList(attachmentInfosField)

    open val attachments: List<Attachment>
        get() = ArrayList(attachmentsField)


    open fun addAttachmentInfo(attachmentInfo: AttachmentInfo) {
        attachmentInfosField.add(attachmentInfo)
    }

    open fun addAttachment(attachment: Attachment) {
        attachmentsField.add(attachment)
    }


    override fun toString(): String {
        return "${DateFormat.getDateInstance(DateFormat.SHORT).format(receivedDate)} $sender: $subject (${attachmentInfos.size} attachments)"
    }

}