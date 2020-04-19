package net.dankito.mail.model


open class MessageChangedListenerOptions(
    val account: MailAccount,
    val folder: String = "inbox",
    val showDebugOutputOnConsole: Boolean = false
) {

    override fun toString(): String {
        return "${account.imapServerAddress} $folder"
    }

}