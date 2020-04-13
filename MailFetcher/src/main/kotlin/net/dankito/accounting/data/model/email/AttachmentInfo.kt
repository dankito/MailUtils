package net.dankito.accounting.data.model.email


open class AttachmentInfo(val name: String, val size: Int, val mimeType: String) {

    override fun toString(): String {
        return "$name ($mimeType)"
    }

}