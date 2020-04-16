package net.dankito.mail.model


open class MailAccount(var username: String, var password: String, var imapServerAddress: String, var imapServerPort: Int) {

    override fun toString(): String {
        return username
    }

}