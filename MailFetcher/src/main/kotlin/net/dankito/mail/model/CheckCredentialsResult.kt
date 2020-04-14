package net.dankito.mail.model


enum class CheckCredentialsResult {

    Ok,

    WrongUsername,

    WrongPassword,

    WrongHostUrl,

    WrongPort,

    UnknownError

}