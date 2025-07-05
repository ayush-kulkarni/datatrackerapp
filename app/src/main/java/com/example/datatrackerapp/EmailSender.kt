package com.example.datatrackerapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class EmailSender {

    suspend fun sendEmail(subject: String, body: String) {
        withContext(Dispatchers.IO) {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(EmailConfig.EMAIL, EmailConfig.PASSWORD)
                }
            })

            try {
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(EmailConfig.EMAIL))
                    addRecipient(Message.RecipientType.TO, InternetAddress(EmailConfig.RECIPIENT_EMAIL))
                    this.subject = subject
                    setText(body)
                }
                Transport.send(message)
                println("Email sent successfully: $subject")
            } catch (e: MessagingException) {
                e.printStackTrace()
            }
        }
    }
}