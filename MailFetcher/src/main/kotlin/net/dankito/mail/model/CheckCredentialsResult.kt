package net.dankito.mail.model


enum class CheckCredentialsResult {

    Ok,

    WrongUsername,

    WrongPassword,

    InvalidImapServerAddress,

    InvalidImapServerPort,

    UnknownError

}