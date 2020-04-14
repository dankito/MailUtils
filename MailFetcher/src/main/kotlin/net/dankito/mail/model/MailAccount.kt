package net.dankito.mail.model


open class MailAccount(var username: String, var password: String, var host: String, var port: Int) {

    override fun toString(): String {
        return username
    }

}