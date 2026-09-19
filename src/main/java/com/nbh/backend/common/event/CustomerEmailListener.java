package com.nbh.backend.common.event;

import com.nbh.backend.common.email.EmailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CustomerEmailListener {

    private final EmailSender emailSender;

    public CustomerEmailListener(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailRequested(CustomerEmailRequestedEvent event) {
        emailSender.send(event.toEmail(), event.subject(), event.textBody());
    }
}
