package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails (HDFC, ICICI).
 */
public final class EmailParser implements MessageParser {

    private static final Pattern PATTERN = Pattern.compile(
            "Date:\\s*(?<date>[^\\n]+)\\n"
                    + "Subject:\\s*Transaction alert on your account\\s*\\n\\s*\\n"
                    + "Dear Customer,\\s*\\n\\s*\\n"
                    + "Your account ending (?<acct>\\d{4}) has been (?<dir>credited|debited) with (?<amt>(?:Rs\\.?|INR)\\s*[0-9,]+(?:\\.[0-9]{2})?)\\.\\s*\\n"
                    + "Merchant / Remarks:\\s*(?<merchant>[^\\n]+)\\s*\\n"
                    + "Transaction reference:\\s*(?<ref>\\d+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher matcher = PATTERN.matcher(m.body());
        if (!matcher.find()) {
            return Optional.empty();
        }

        String acct = matcher.group("acct");
        OffsetDateTime occurredAt = Dates.ist(matcher.group("date"));
        Direction dir = matcher.group("dir").equalsIgnoreCase("credited")
                ? Direction.CREDIT : Direction.DEBIT;
        BigDecimal amount = Amounts.first(matcher.group("amt"));
        String merchant = matcher.group("merchant").trim();

        if (occurredAt == null || amount == null) {
            return Optional.empty();
        }

        return Optional.of(new ParsedTxn(
                acct,
                occurredAt,
                dir,
                amount,
                merchant,
                Amounts.statedBalance(m.body()),
                m.messageId()
        ));
    }
}
