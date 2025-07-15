package com.example.datatrackerapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Class responsible for sending emails.
 * This class uses JavaMail API to send emails via SMTP.
 */
class EmailSender {

    /**
     * Sends an email with the given subject and body.
     * This function is a suspend function, meaning it can be called from a coroutine
     * and will perform network operations on an IO dispatcher.
     *
     * @param subject The subject of the email.
     * @param body The body content of the email.
     */
    suspend fun sendEmail(subject: String, body: String) {
        // Switch to the IO dispatcher for network operations
        withContext(Dispatchers.IO) {
            // Configure properties for the mail session
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
            }
            // Create a mail session with an authenticator
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    // Provide authentication credentials from EmailConfig
                    return PasswordAuthentication(EmailConfig.EMAIL, EmailConfig.PASSWORD)
                }
            })

            try {
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(EmailConfig.EMAIL))
                    // Set the recipient of the email
                    addRecipient(Message.RecipientType.TO, InternetAddress(EmailConfig.RECIPIENT_EMAIL))
                    // Set the subject of the email
                    this.subject = subject
                    // Set the body content of the email
                    setText(body)
                }
                // Send the email
                Transport.send(message)
                println("Email sent successfully: $subject")
            } catch (e: MessagingException) {
                // Print stack trace in case of an error during email sending
                e.printStackTrace()
            }
        }
    }
}