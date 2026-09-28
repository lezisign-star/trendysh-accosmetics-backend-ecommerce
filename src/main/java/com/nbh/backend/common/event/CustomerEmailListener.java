package com.nbh.backend.common.event;

import com.nbh.backend.common.email.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;

/**
 * Sends a requested email once the transaction that asked for it has committed, on the email executor
 * (see {@link EmailExecutorConfig} for why not on the request thread). A send that fails is logged, not
 * thrown: by then the request has already been answered.
 */
@Slf4j
@Component
public class CustomerEmailListener {

    private final EmailSender emailSender;
    private final Executor executor;

    public CustomerEmailListener(EmailSender emailSender, @Qualifier("emailTaskExecutor") Executor executor) {
        this.emailSender = emailSender;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailRequested(CustomerEmailRequestedEvent event) {
        executor.execute(() -> {
            try {
                if (event.htmlBody() != null) {
                    emailSender.send(event.toEmail(), event.subject(), event.textBody(), event.htmlBody());
                } else {
                    emailSender.send(event.toEmail(), event.subject(), event.textBody());
                }
            } catch (RuntimeException e) {
                log.error("Couldn't send \"{}\" to {}: {}", event.subject(), event.toEmail(), e.getMessage());
            }
        });
    }
}
