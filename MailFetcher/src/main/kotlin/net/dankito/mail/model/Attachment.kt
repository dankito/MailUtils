package net.dankito.mail.model


open class Attachment(name: String, size: Int, mimeType: String, val content: ByteArray)
    : AttachmentInfo(name, size, mimeType) {
}