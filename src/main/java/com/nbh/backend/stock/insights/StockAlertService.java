package com.nbh.backend.stock.insights;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.common.event.LowStockThresholdCrossedEvent;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.stock.insights.StockInsightsDtos.ForecastRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The stock-alert emails: one immediately when a variant drops below its reorder level, and one
 * morning digest of everything that is out, will run out within a week, or is at its reorder level.
 * Goes to enabled staff who haven't been switched off in Settings. Never mentions demo-data products —
 * a demo must not email the client's real staff about stock that doesn't exist.
 *
 * <p>Not {@code @Transactional} on purpose: it makes network calls (the email provider), and a
 * database connection shouldn't be held while waiting on one. Everything it reads arrives fully loaded.
 */
@Slf4j
@Service
public class StockAlertService {

    private static final int DIGEST_ROW_LIMIT = 40;

    private final StaffUserRepository staffUsers;
    private final EmailSender emailSender;
    private final StockForecastService forecastService;
    private final StockAlertSettingsService settingsService;
    private final ProductVariantRepository variantRepository;
    private final String frontendUrl;

    public StockAlertService(StaffUserRepository staffUsers, EmailSender emailSender,
                              StockForecastService forecastService, StockAlertSettingsService settingsService,
                              ProductVariantRepository variantRepository,
                              @Value("${app.frontend-url}") String frontendUrl) {
        this.staffUsers = staffUsers;
        this.emailSender = emailSender;
        this.forecastService = forecastService;
        this.settingsService = settingsService;
        this.variantRepository = variantRepository;
        this.frontendUrl = frontendUrl;
    }

    public List<String> recipientEmails() {
        return staffUsers.findByEnabledTrueAndDeletedFalseAndReceivesStockAlertsTrue().stream()
                .map(StaffUser::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .distinct()
                .toList();
    }

    /** @return how many people the alert went to */
    public int sendLowStockAlert(LowStockThresholdCrossedEvent event) {
        ProductVariant variant = variantRepository.findWithProductById(event.variantId()).orElse(null);
        if (variant == null || variant.getProduct().isDeleted() || variant.getProduct().isMockData()) {
            return 0;
        }
        List<String> to = recipientEmails();
        if (to.isEmpty()) return 0;

        String name = displayName(variant.getProduct().getName(), variant.getVariantName());
        String subject = "Low stock: " + name + " (" + qty(event.onHandAfter()) + " left)";
        String text = name + "\n"
                + "SKU: " + variant.getSku() + "\n"
                + "Now in stock: " + qty(event.onHandAfter()) + " (was " + qty(event.onHandBefore()) + ")\n"
                + "Reorder level: " + qty(event.reorderLevel()) + "\n\n"
                + "It has dropped below its reorder level. Restock soon so it doesn't run out.\n"
                + "Stock levels: " + stockLevelsLink() + "\n";
        String html = "<p><strong>" + esc(name) + "</strong><br>SKU: " + esc(variant.getSku()) + "</p>"
                + "<p>Now in stock: <strong>" + qty(event.onHandAfter()) + "</strong> (was " + qty(event.onHandBefore())
                + ")<br>Reorder level: " + qty(event.reorderLevel()) + "</p>"
                + "<p>It has dropped below its reorder level. Restock soon so it doesn't run out.</p>"
                + "<p><a href=\"" + stockLevelsLink() + "\">Open stock levels</a></p>";
        return sendToAll(to, subject, text, html);
    }

    /** Sends the morning digest unless it already went out today, is switched off, or there is nothing to report. */
    public int sendDigestIfDue(LocalDate today) {
        StockAlertSettings settings = settingsService.get();
        if (!settings.isDigestEnabled() || today.equals(settings.getLastDigestOn())) return 0;

        List<ForecastRow> rows = digestRows();
        if (rows.isEmpty()) return 0;
        List<String> to = recipientEmails();
        if (to.isEmpty()) {
            log.info("Stock digest has {} item(s) but nobody is set to receive stock alerts", rows.size());
            return 0;
        }
        sendDigest(to, rows);
        settingsService.markDigestSent(today);
        return rows.size();
    }

    /** Sends the current digest — even an all-clear one — to one person, so they can check emails work. */
    public int sendTestDigest(StaffUser to) {
        List<ForecastRow> rows = digestRows();
        sendDigest(List.of(to.getEmail()), rows);
        return rows.size();
    }

    List<ForecastRow> digestRows() {
        return forecastService.forecast().rows().stream()
                .filter(StockForecastService::needsAttention)
                .filter(r -> !r.demo())
                .toList();
    }

    private void sendDigest(List<String> to, List<ForecastRow> rows) {
        if (rows.isEmpty()) {
            sendToAll(to, "Stock digest: nothing running low",
                    "Nothing is out of stock, running out within a week, or below its reorder level.\n"
                            + "Stock levels: " + stockLevelsLink() + "\n",
                    "<p>Nothing is out of stock, running out within a week, or below its reorder level.</p>"
                            + "<p><a href=\"" + stockLevelsLink() + "\">Open stock levels</a></p>");
            return;
        }
        List<ForecastRow> shown = rows.size() > DIGEST_ROW_LIMIT ? rows.subList(0, DIGEST_ROW_LIMIT) : rows;
        StringBuilder text = new StringBuilder("These products need attention:\n\n");
        StringBuilder html = new StringBuilder("<p>These products need attention:</p><table cellpadding=\"6\" "
                + "style=\"border-collapse:collapse;font-size:14px\"><tr style=\"text-align:left\">"
                + "<th>Product</th><th>In stock</th><th>Sells/day</th><th>Days left</th><th>Suggested restock</th></tr>");
        for (ForecastRow r : shown) {
            String name = displayName(r.productName(), r.variantName());
            String days = r.daysOfCover() == null ? "-" : qty(r.daysOfCover());
            text.append("- ").append(name).append(" (").append(r.sku()).append("): ")
                    .append(qty(r.available())).append(" in stock");
            if (r.daysOfCover() != null) {
                text.append(", ").append(qty(r.avgPerDay())).append("/day, about ").append(days).append(" day(s) left");
            }
            if (r.suggestedRestockQty().signum() > 0) {
                text.append(", suggested restock ").append(qty(r.suggestedRestockQty()));
            }
            if (r.lowConfidence()) text.append(" (few sales so far - rough estimate)");
            text.append("\n");
            html.append("<tr><td>").append(esc(name)).append("<br><small>").append(esc(r.sku())).append("</small></td><td>")
                    .append(qty(r.available())).append("</td><td>").append(r.daysOfCover() == null ? "-" : qty(r.avgPerDay()))
                    .append("</td><td>").append(days).append("</td><td>")
                    .append(r.suggestedRestockQty().signum() > 0 ? qty(r.suggestedRestockQty()) : "-").append("</td></tr>");
        }
        html.append("</table>");
        if (rows.size() > shown.size()) {
            String more = "+" + (rows.size() - shown.size()) + " more — open stock levels to see them all.";
            text.append("\n").append(more).append("\n");
            html.append("<p>").append(more).append("</p>");
        }
        text.append("\nStock levels: ").append(stockLevelsLink()).append("\n");
        html.append("<p><a href=\"").append(stockLevelsLink()).append("\">Open stock levels</a></p>");
        String subject = "Stock digest: " + (rows.size() == 1 ? "1 product needs" : rows.size() + " products need") + " attention";
        sendToAll(to, subject, text.toString(), html.toString());
    }

    private int sendToAll(List<String> to, String subject, String text, String html) {
        int sent = 0;
        for (String address : to) {
            try {
                emailSender.send(address, subject, text, html);
                sent++;
            } catch (Exception e) {
                log.error("Couldn't send stock alert '{}' to {}: {}", subject, address, e.getMessage());
            }
        }
        return sent;
    }

    /**
     * Where the alerts send staff. It was the Forecast screen, but the shop keeps stock simple now (SIMPLE_STOCK in the
     * frontend's adminAccess.ts) and that screen is switched off, so the link goes to Stock levels, which always works.
     */
    private String stockLevelsLink() {
        return frontendUrl.replaceAll("/+$", "") + "/admin/stock";
    }

    private static String displayName(String product, String variant) {
        return variant == null || variant.isBlank() ? product : product + " — " + variant;
    }

    private static String qty(BigDecimal value) {
        if (value == null) return "-";
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
