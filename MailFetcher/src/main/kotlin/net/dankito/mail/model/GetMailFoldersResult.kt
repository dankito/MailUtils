package net.dankito.mail.model


open class GetMailFoldersResult(
    val successful: Boolean,
    val folders: List<MailFolder>,
    val error: Exception? = null
)