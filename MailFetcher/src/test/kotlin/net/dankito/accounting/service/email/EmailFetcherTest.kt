package net.dankito.accounting.service.email

import net.dankito.mail.EmailFetcher
import net.dankito.mail.model.CheckCredentialsResult
import net.dankito.mail.model.Email
import net.dankito.mail.model.FetchEmailOptions
import net.dankito.mail.model.MailAccount
import net.dankito.utils.Stopwatch
import net.dankito.utils.ThreadPool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


@Disabled // don't run automatically, needs email credentials, see constants in companion object
class EmailFetcherTest {

    companion object {
        // set your email credentials here
        private const val MailAccountUsername = ""

        private const val MailAccountPassword = ""

        private const val MailAccountImapServerAddress = ""

        private const val MailAccountImapServerPort = 993


        private const val ChunkSize = 10

        // TODO: i hope these message ids also exist on your account; adjust if needed
        private val MessageIdsToFetch = listOf<Long>(2, 3, 4, 5, 6) // specify 5 message ids to also test chunkSize

        private const val ShowJavaMailDebugLogOutput = false

        private val log = LoggerFactory.getLogger(EmailFetcherTest::class.java)
    }


    private val underTest = EmailFetcher(ThreadPool())


    @Test
    fun connect_NotExistingServerAddress() {

        // given
        val account = MailAccount(MailAccountUsername, MailAccountPassword, "i_dont_exist.com", MailAccountImapServerPort)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.InvalidImapServerAddress)
    }

    @Test
    fun connect_InvalidImapServerPort() {

        // given
        val account = MailAccount(MailAccountUsername, MailAccountPassword, MailAccountImapServerAddress, 0)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.InvalidImapServerPort)
    }

    @Test
    fun connect_WrongUsername() {

        // given
        val account = MailAccount(UUID.randomUUID().toString(), MailAccountPassword, MailAccountImapServerAddress, MailAccountImapServerPort)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.WrongUsername)
    }

    @Test
    fun connect_WrongPassword() {

        // given
        val account = MailAccount(MailAccountUsername, UUID.randomUUID().toString(), MailAccountImapServerAddress, MailAccountImapServerPort)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.WrongPassword)
    }

    @Test
    fun connect_CredentialsAreCorrect() {

        // given
        val account = MailAccount(MailAccountUsername, MailAccountPassword, MailAccountImapServerAddress, MailAccountImapServerPort)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.Ok)
    }


    @Test
    fun getMailFolders() {

        // given
        val account = MailAccount(MailAccountUsername, MailAccountPassword, MailAccountImapServerAddress, MailAccountImapServerPort)

        // when
        val result = underTest.getMailFolders(account)

        // then
        assertThat(result.successful).isTrue()
        assertThat(result.error).isNull()
        assertThat(result.folders).isNotEmpty
    }


    @Test
    fun fetchEmails() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions()) {
            retrievedMails.set(it.allRetrievedMails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).isNotEmpty

        retrievedMails.get().forEach { mail ->
            assertThat(mail.messageId).isNull()
            assertThat(mail.body).isNull()
            assertThat(mail.attachmentInfos).isEmpty()
            assertThat(mail.contentType).isNull()
        }
    }

    @Test
    fun fetchAllMessagesFromThisMessageIdOn() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)

        // first get all messages and than retrieve last max 10 mails by message id
        val allMails = AtomicReference<List<Email>>(null)
        val allMailsCountDownLatch = CountDownLatch(1)

        underTest.fetchMailsAsync(createFetchEmailOptions(retrieveMessageIds = true)) {
            allMails.set(it.allRetrievedMails)

            allMailsCountDownLatch.countDown()
        }

        try { allMailsCountDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }

        val allMessageIds = allMails.get().mapNotNull { it.messageId }.sorted()
        val messageIdsToRetrieve = if (allMessageIds.size > 10) allMessageIds.subList(allMessageIds.size - 10, allMessageIds.size) else listOf(allMessageIds.last())

        val messagesStartId = messageIdsToRetrieve.sorted().first()


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(messagesStartId, retrieveMessageIds = true)) {
            retrievedMails.set(it.allRetrievedMails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).hasSize(messageIdsToRetrieve.size)
        assertThat(retrievedMails.get().mapNotNull { it.messageId }).containsExactly(*messageIdsToRetrieve.toTypedArray())
    }

    @Test
    fun fetchOnlyMailsWithMessageIds() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(null, MessageIdsToFetch, true)) {
            retrievedMails.set(it.allRetrievedMails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).hasSize(MessageIdsToFetch.size)
        assertThat(retrievedMails.get().map { it.messageId }).containsExactly(*MessageIdsToFetch.toTypedArray())
    }

    @Test
    fun fetchOnlyMailsWithMessageIdsChunked() {

        // given

        val chunkSize = 2
        val retrievedMails = AtomicReference<List<Email>>(null)
        val countCallbackInvocations = AtomicInteger(0)
        var previousChunkSize = 0
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(null, MessageIdsToFetch, true, chunkSize = chunkSize)) { result ->
            countCallbackInvocations.getAndIncrement()

            if (result.completed == false) {
                if (result.allRetrievedMails.size % chunkSize == 0) { // last chunk may has a chunk size < chunkSize
                    assertThat(result.retrievedChunk).hasSize(chunkSize)
                }
                else { // to find if retrievedChunk never has chunkSize
                    assertThat(previousChunkSize).isEqualTo(chunkSize)
                }

                previousChunkSize = result.retrievedChunk.size
            }
            else {
                retrievedMails.set(result.allRetrievedMails)

                countDownLatch.countDown()
            }
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).hasSize(MessageIdsToFetch.size)
        assertThat(retrievedMails.get().map { it.messageId }).containsExactly(*MessageIdsToFetch.toTypedArray())
    }

    @Test
    fun fetchEmailsChunked() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countCallbackInvocations = AtomicInteger(0)
        var previousChunkSize = 0
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(chunkSize = ChunkSize)) { result ->
            countCallbackInvocations.getAndIncrement()

            if (result.completed == false) {
                if (result.allRetrievedMails.size % ChunkSize == 0) { // last chunk may has a chunk size < ChunkSize
                    assertThat(result.retrievedChunk).hasSize(ChunkSize)
                }
                else { // to find if retrievedChunk never has ChunkSize
                    assertThat(previousChunkSize).isEqualTo(ChunkSize)
                }

                previousChunkSize = result.retrievedChunk.size
            }
            else {
                retrievedMails.set(result.allRetrievedMails)

                countDownLatch.countDown()
            }
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).isNotEmpty
        assertThat(countCallbackInvocations.get()).isEqualTo(Math.ceil(retrievedMails.get().size / ChunkSize.toDouble()).toInt() + 1)
    }

    @Test
    fun fetchEmailMessageIds() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(retrieveMessageIds = true)) {
            retrievedMails.set(it.allRetrievedMails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).isNotEmpty

        retrievedMails.get().forEach { mail ->
            assertThat(mail.messageId).isNotNull()
            assertThat(mail.body).isNull()
            assertThat(mail.attachmentInfos).isEmpty()
            assertThat(mail.contentType).isNull()
        }
    }

    @Test
    fun fetchEmailBodies() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(retrievePlainTextBodies = true, retrieveHtmlBodies = true)) {
            retrievedMails.set(it.allRetrievedMails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(3, TimeUnit.MINUTES) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).isNotEmpty

        retrievedMails.get().forEach { mail ->
            assertThat(mail.messageId).isNull()
            assertThat(mail.attachmentInfos).isEmpty()
            assertThat(mail.contentType).isNotNull()
        }
    }

    @Test
    fun fetchEmailAttachmentInfos() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(retrieveAttachmentInfos = true)) {
            retrievedMails.set(it.allRetrievedMails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).isNotEmpty

        val allAttachmentInfos = retrievedMails.get().flatMap { it.attachmentInfos }
        val allAttachments = retrievedMails.get().flatMap { it.attachments }

        assertThat(allAttachmentInfos).isNotEmpty
        assertThat(allAttachments).isEmpty()

        allAttachmentInfos.forEach { attachment ->
            assertThat(attachment.mimeType).isNotEmpty()
            assertThat(attachment.size).isGreaterThan(0)
        }

        retrievedMails.get().forEach { mail ->
            assertThat(mail.messageId).isNull()
            assertThat(mail.body).isNull()
            assertThat(mail.contentType).isNotNull()
        }
    }

    @Test
    fun downloadEmailAttachments() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(downloadAttachments = true)) {
            retrievedMails.set(it.allRetrievedMails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(10, TimeUnit.MINUTES) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).isNotEmpty

        val allAttachmentInfos = retrievedMails.get().flatMap { it.attachmentInfos }
        val allAttachments = retrievedMails.get().flatMap { it.attachments }

        assertThat(allAttachmentInfos).isEmpty()
        assertThat(allAttachments).isNotEmpty

        allAttachments.forEach { attachment ->
            assertThat(attachment.mimeType).isNotEmpty()
            assertThat(attachment.size).isGreaterThan(0)
            assertThat(attachment.content).isNotEmpty()
        }

        retrievedMails.get().forEach { mail ->
            assertThat(mail.messageId).isNull()
            assertThat(mail.body).isNull()
            assertThat(mail.contentType).isNotNull()
        }
    }


    @Disabled
    @ParameterizedTest
    @ValueSource(ints = [ 1, 10, 25, 50, 100 ] )
    @Tag("Measure Chunk Size Performance")
    fun bestChunkSizeDetection_FetchCompleteMailsInChunksOf(chunkSize: Int) {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)

        val overallStopwatch = Stopwatch()
        var chunkStopwatch = Stopwatch()


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(retrieveMessageIds = true, retrievePlainTextBodies = true,
            retrieveHtmlBodies = true, downloadAttachments = true, chunkSize = chunkSize)) { result ->

            chunkStopwatch.stopAndLog("Retrieving chunk of ${result.retrievedChunk.size} mails", log)
            chunkStopwatch = Stopwatch()

            if (result.completed) {
                retrievedMails.set(result.allRetrievedMails)

                countDownLatch.countDown()
            }
        }

        try { countDownLatch.await(15, TimeUnit.MINUTES) } catch (ignored: Exception) { }


        // then

        overallStopwatch.stopAndLog("Retrieving ${retrievedMails.get()?.size} in chunks of $chunkSize", log)
    }


    private fun createFetchEmailOptions(retrieveAllMessagesFromThisMessageIdOn: Long? = null,
                                        retrieveOnlyMessagesWithTheseIds: List<Long>? = null,
                                        retrieveMessageIds: Boolean = false, retrievePlainTextBodies: Boolean = false,
                                        retrieveHtmlBodies: Boolean = false, retrieveAttachmentInfos: Boolean = false,
                                        downloadAttachments: Boolean = false, chunkSize: Int = -1): FetchEmailOptions {

        val account = MailAccount(MailAccountUsername, MailAccountPassword, MailAccountImapServerAddress, MailAccountImapServerPort)

        return FetchEmailOptions(account, retrieveAllMessagesFromThisMessageIdOn, retrieveOnlyMessagesWithTheseIds, retrieveMessageIds,
            retrievePlainTextBodies, retrieveHtmlBodies, retrieveAttachmentInfos, downloadAttachments, chunkSize, ShowJavaMailDebugLogOutput)
    }

}