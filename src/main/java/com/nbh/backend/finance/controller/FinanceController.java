package com.nbh.backend.finance.controller;

import com.nbh.backend.finance.dto.FinanceDtos.LedgerEntry;
import com.nbh.backend.finance.dto.FinanceDtos.PriceHistoryEntry;
import com.nbh.backend.finance.dto.FinanceDtos.Summary;
import com.nbh.backend.finance.dto.FinanceDtos.Trail;
import com.nbh.backend.finance.service.FinanceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Money the website has taken, for the people who oversee it ({@code FINANCE_VIEW}: the owner, the developer and managers;
 * not the read-only Viewer, owner 2026-10-23). {@code from} and {@code to} are instants (a period is [from, to)); left out, the period is the last 30
 * days. Everything here only reads. See {@link FinanceService}.
 */
@RestController
@RequestMapping("/api/v1/admin/finance")
@PreAuthorize("hasAuthority('PERM_FINANCE_VIEW')")
public class FinanceController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FinanceService finance;

    public FinanceController(FinanceService finance) {
        this.finance = finance;
    }

    @GetMapping("/summary")
    public Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return finance.summary(from, to);
    }

    /** Payments received in the period with the path each took. {@code method}: MPESA, BANK or CASH; {@code source}: ALL, STAFF or CUSTOMER. */
    @GetMapping("/payments")
    public Page<LedgerEntry> payments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        // Built here, not taken from the client: the query orders its own rows, and a client's sort would be spliced into SQL.
        PageRequest paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return finance.ledger(from, to, method, source, q, paging);
    }

    /** The same payments as a spreadsheet file (opens in Excel). */
    @GetMapping(value = "/payments.csv", produces = "text/csv")
    public ResponseEntity<byte[]> paymentsCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String q) {
        FinanceService.Period period = finance.period(from, to);
        String csv = finance.ledgerCsv(period.from(), period.to(), method, source, q);
        String name = "website-money-in-" + period.from().toString().substring(0, 10) + "-to-"
                + period.to().toString().substring(0, 10) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, new MediaType("text", "csv", StandardCharsets.UTF_8).toString())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** Changes of normal selling prices in the period, kept automatically with who and when (no reason is asked). */
    @GetMapping("/price-history")
    public Page<PriceHistoryEntry> priceHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        PageRequest paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return finance.priceHistory(from, to, q, paging);
    }

    /** The life of one order's money, oldest first. */
    @GetMapping("/orders/{orderId}/trail")
    public Trail trail(@PathVariable UUID orderId) {
        return finance.trail(orderId);
    }
}
