package net.dankito.mail.model


class FetchEmailsResult(
    val completed: Boolean,
    val allRetrievedMails: List<Email>,
    val retrievedChunk: List<Email> = listOf(),
    val error: Exception? = null
) {

    constructor(error: Exception) : this(true, listOf(), listOf(), error)


    override fun toString(): String {
        return "Completed? $completed ${allRetrievedMails.size} retrieved, error = $error"
    }

}