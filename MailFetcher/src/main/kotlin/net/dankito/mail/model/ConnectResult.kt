package net.dankito.mail.model

import javax.mail.Store


open class ConnectResult(
    val successful: Boolean,
    val error: Exception? = null,
    val store: Store? = null
) {

    constructor(error: Exception) : this(false, error)

    constructor(store: Store) : this(true, null, store)

}