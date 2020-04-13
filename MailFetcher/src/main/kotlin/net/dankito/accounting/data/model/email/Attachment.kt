package net.dankito.accounting.data.model.email


open class Attachment(name: String, size: Int, mimeType: String, val content: ByteArray)
    : AttachmentInfo(name, size, mimeType) {
}