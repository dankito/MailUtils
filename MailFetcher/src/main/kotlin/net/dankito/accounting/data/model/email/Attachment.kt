package net.dankito.accounting.data.model.email


class Attachment(val name: String, val size: Int, val mimeType: String) {

    override fun toString(): String {
        return "$name ($mimeType)"
    }

}