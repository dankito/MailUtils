package net.dankito.accounting.data.model.email

import java.text.DateFormat
import java.util.*


class Email(val sender: String,
            val subject: String,
            val receivedDate: Date,
            val sentDate: Date? = null
) {

    private val attachmentsField = mutableListOf<Attachment>()


    var messageId: Long? = null


    var size: Long? = null


    var plainTextBodyInfo: EmailBodyInfo? = null

    var plainTextBody: String? = null

    var htmlBodyInfo: EmailBodyInfo? = null

    var htmlBody: String? = null

    val body: String?
        get() = plainTextBody ?: htmlBody


    val attachments: List<Attachment>
        get() = ArrayList(attachmentsField)


    fun addAttachment(attachment: Attachment) {
        attachmentsField.add(attachment)
    }


    override fun toString(): String {
        return "${DateFormat.getDateInstance(DateFormat.SHORT).format(receivedDate)} $sender: $subject (${attachments.size} attachments)"
    }

}