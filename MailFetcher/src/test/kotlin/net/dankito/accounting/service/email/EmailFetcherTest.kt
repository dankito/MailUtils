package net.dankito.accounting.service.email

import net.dankito.accounting.data.model.email.Email
import net.dankito.accounting.data.model.email.EmailAccount
import net.dankito.accounting.data.model.email.FetchEmailOptions
import net.dankito.utils.ThreadPool
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


@Ignore // don't run automatically, needs email credentials, see createFetchEmailOptions() at end of class
class EmailFetcherTest {

    companion object {
        private const val ChunkSize = 10
    }


    private val underTest = EmailFetcher(ThreadPool())


    @Test
    fun fetchEmails() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchEmailsAsync(createFetchEmailOptions()) {
            retrievedMails.set(it.emails)

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

        underTest.fetchEmailsAsync(createFetchEmailOptions(chunkSize = ChunkSize)) { result ->
            countCallbackInvocations.getAndIncrement()

            if (result.completed) {
                retrievedMails.set(result.emails)

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

        underTest.fetchEmailsAsync(createFetchEmailOptions(retrieveMessageIds = true)) {
            retrievedMails.set(it.emails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).isNotEmpty

        retrievedMails.get().forEach { mail ->
            assertThat(mail.messageId).isNotNull()
            assertThat(mail.body).isNull()
            assertThat(mail.attachments).isEmpty()
        }
    }

    @Test
    fun fetchEmailBodies() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchEmailsAsync(createFetchEmailOptions(retrievePlainTextBodies = true, retrieveHtmlBodies = true)) {
            retrievedMails.set(it.emails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).isNotEmpty

        retrievedMails.get().forEach { mail ->
            assertThat(mail.messageId).isNull()
            assertThat(mail.body).isNotNull()
            assertThat(mail.attachments).isEmpty()
        }
    }

    @Test
    fun fetchEmailAttachmentNames() {

        // given

        val retrievedMails = AtomicReference<List<Email>>(null)
        val countDownLatch = CountDownLatch(1)


        // when

        underTest.fetchEmailsAsync(createFetchEmailOptions(retrieveAttachmentNames = true)) {
            retrievedMails.set(it.emails)

            countDownLatch.countDown()
        }

        try { countDownLatch.await(60, TimeUnit.SECONDS) } catch (ignored: Exception) { }


        // then

        assertThat(retrievedMails.get()).isNotNull
        assertThat(retrievedMails.get()).isNotEmpty

        val allAttachments = retrievedMails.get().flatMap { it.attachments }

        assertThat(allAttachments).isNotEmpty

        allAttachments.forEach { attachment ->
            assertThat(attachment.mimeType).isNotEmpty()
            assertThat(attachment.size).isGreaterThan(0)
        }

        retrievedMails.get().forEach { mail ->
            assertThat(mail.messageId).isNull()
            assertThat(mail.body).isNull()
        }
    }


    private fun createFetchEmailOptions(retrieveMessageIds: Boolean = false, retrievePlainTextBodies: Boolean = false,
                                        retrieveHtmlBodies: Boolean = false, retrieveAttachmentNames: Boolean = false,
                                        chunkSize: Int = -1): FetchEmailOptions {

        val account = EmailAccount("", "", "", 0) // set your email credentials here

        return FetchEmailOptions(account, retrieveMessageIds, retrievePlainTextBodies, retrieveHtmlBodies,
            retrieveAttachmentNames, chunkSize, false)
    }

}