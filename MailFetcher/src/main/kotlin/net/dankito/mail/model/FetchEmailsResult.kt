package net.dankito.mail.model


class FetchEmailsResult(val completed: Boolean, val emails: List<Email>, val error: Exception? = null) {

    override fun toString(): String {
        return "Completed? $completed ${emails.size} retrieved, error = $error"
    }

}