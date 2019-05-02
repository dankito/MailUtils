package net.dankito.accounting.data.model.email


class FetchEmailsResult(val completed: Boolean, val emails: List<Email>, val error: Exception? = null) {

    override fun toString(): String {
        return "Completed? $completed ${emails.size} retrieved, error = $error"
    }

}