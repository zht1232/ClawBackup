package clawx.backup.integration;

import clawx.backup.config.BackupConfig;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;

/**
 * 邮件通知（SMTP，基于 JavaMail）。
 */
public final class EmailNotifier {

    private EmailNotifier() {
    }

    public static void send(BackupConfig config, String subject, String body) throws Exception {
        String host = config.getEmailHost();
        int port = config.getEmailPort();
        boolean ssl = config.isEmailSsl();
        String user = config.getEmailUsername();
        String pass = config.getEmailPassword();
        String from = config.getEmailFrom().isEmpty() ? user : config.getEmailFrom();
        List<String> to = config.getEmailTo();
        if (user.isEmpty() || pass.isEmpty() || to.isEmpty()) {
            throw new IllegalStateException("邮件配置不完整（username/password/to 不能为空）");
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "15000");
        if (ssl) {
            props.put("mail.smtp.socketFactory.port", String.valueOf(port));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        for (String t : to) {
            msg.addRecipient(Message.RecipientType.TO, new InternetAddress(t.trim()));
        }
        msg.setSubject(subject);
        msg.setText(body);
        Transport.send(msg);
    }
}
