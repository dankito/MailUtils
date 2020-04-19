package net.dankito.mail.model

import javax.mail.Folder
import javax.mail.Store


open class ConnectAndOpenFolderResult(
    successful: Boolean,
    error: Exception? = null,
    store: Store? = null,
    val folder: Folder? = null,
    val type: ConnectAndOpenFolderResultErrorType? = null
) : ConnectResult(successful, error, store) {

    constructor(error: Exception, type: ConnectAndOpenFolderResultErrorType) : this(false, error, null, null, type)

    constructor(store: Store, folder: Folder) : this(true, null, store, folder)

}