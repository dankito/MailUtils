package net.dankito.accounting.service.email

import net.dankito.mail.EmailFetcher
import net.dankito.mail.model.CheckCredentialsResult
import net.dankito.mail.model.Email
import net.dankito.mail.model.FetchEmailOptions
import net.dankito.mail.model.MailAccount
import net.dankito.utils.ThreadPool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
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

        private const val MailAccountImapUrl = ""

        private const val MailAccountPort = 0


        private const val ChunkSize = 10

        private const val ShowJavaMailDebugLogOutput = false
    }


    private val underTest = EmailFetcher(ThreadPool())


    @Test
    fun connect_NotExistingHost() {

        // given
        val account = MailAccount(MailAccountUsername, MailAccountPassword, "i_dont_exist.com", MailAccountPort)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.WrongHostUrl)
    }

    @Test
    fun connect_WrongPort() {

        // given
        val account = MailAccount(MailAccountUsername, MailAccountPassword, MailAccountImapUrl, 0)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.WrongPort)
    }

    @Test
    fun connect_WrongUsername() {

        // given
        val account = MailAccount(UUID.randomUUID().toString(), MailAccountPassword, MailAccountImapUrl, MailAccountPort)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.WrongUsername)
    }

    @Test
    fun connect_WrongPassword() {

        // given
        val account = MailAccount(MailAccountUsername, UUID.randomUUID().toString(), MailAccountImapUrl, MailAccountPort)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.WrongPassword)
    }

    @Test
    fun connect_CredentialsAreCorrect() {

        // given
        val account = MailAccount(MailAccountUsername, MailAccountPassword, MailAccountImapUrl, MailAccountPort)

        // when
        val result = underTest.checkAreCredentialsCorrect(account)

        // then
        assertThat(result).isEqualByComparingTo(CheckCredentialsResult.Ok)
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
    }

    @Test
    fun fetchEmailsChunked() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countCallbackInvocations = AtomicInteger(0)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchMailsAsync(createFetchEmailOptions(chunkSize = ChunkSize)) { result ->
            countCallbackInvocations.getAndIncrement()

            if (result.completed == false) {
                assertThat(result.retrievedChunk).hasSize(ChunkSize)
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
        assertThat(countCallbackInvocations.get()).isEqualTo(Math.ceil(retrievedMails.get().size / ChunkSize.toDouble()).toInt())
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
            assertThat(mail.body).isNotNull()
            assertThat(mail.attachmentInfos).isEmpty()
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

        try { countDownLatch.await(60, TimeUnit.MINUTES) } catch (ignored: Exception) { } // TODO: undo


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
        }
    }


    private fun createFetchEmailOptions(retrieveMessageIds: Boolean = false, retrievePlainTextBodies: Boolean = false,
                                        retrieveHtmlBodies: Boolean = false, retrieveAttachmentInfos: Boolean = false,
                                        downloadAttachments: Boolean = false, chunkSize: Int = -1): FetchEmailOptions {

        val account = MailAccount(MailAccountUsername, MailAccountPassword, MailAccountImapUrl, MailAccountPort)

        return FetchEmailOptions(account, retrieveMessageIds, retrievePlainTextBodies, retrieveHtmlBodies,
            retrieveAttachmentInfos, downloadAttachments, chunkSize, ShowJavaMailDebugLogOutput)
    }

}