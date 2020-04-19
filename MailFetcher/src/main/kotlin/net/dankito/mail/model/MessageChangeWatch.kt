package net.dankito.mail.model

import javax.mail.Folder


class MessageChangeWatch(
    val account: MailAccount,
    val folder: Folder
) : IMessageChangeWatch {

    override fun toString(): String {
        return "${account.imapServerAddress} ${account.username} ${folder.name}"
    }

}