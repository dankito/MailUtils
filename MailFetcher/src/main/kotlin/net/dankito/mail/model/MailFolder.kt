package net.dankito.mail.model


open class MailFolder(
    val name: String,
    val messageCount: Int,
    val subFolders: List<MailFolder> = listOf()
) {

    override fun toString(): String {
        return name
    }

}