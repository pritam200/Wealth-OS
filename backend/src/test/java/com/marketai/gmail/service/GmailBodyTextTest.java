package com.marketai.gmail.service;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the model is given to read for an email body. */
class GmailBodyTextTest {

    private final GmailClientService client = new GmailClientService();

    private static MessagePart leaf(String mime, String content) {
        return new MessagePart().setMimeType(mime).setBody(new MessagePartBody()
            .setData(Base64.getUrlEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))));
    }

    private static MessagePart multi(String mime, MessagePart... parts) {
        return new MessagePart().setMimeType(mime).setParts(List.of(parts));
    }

    private String body(MessagePart root) {
        return client.getBodyText(new Message().setPayload(root));
    }

    @Test
    @DisplayName("an email sent as both plain text and HTML is read once, not twice")
    void alternativePartsAreReadOnce() {
        String text = body(multi("multipart/alternative",
            leaf("text/plain", "Rs 250.00 spent at Swiggy on 10-09-2026"),
            leaf("text/html", "<p>Rs 250.00 spent at <b>Swiggy</b> on 10-09-2026</p>")));

        assertThat(text.split("250\\.00", -1)).hasSize(2);   // exactly one occurrence
        assertThat(text).contains("Swiggy");
    }

    @Test
    @DisplayName("the HTML alternative is preferred, even when nested with its inline images")
    void nestedHtmlAlternativeIsPreferred() {
        String text = body(multi("multipart/mixed",
            multi("multipart/alternative",
                leaf("text/plain", "See the HTML version"),
                multi("multipart/related", leaf("text/html", "<td>Amount</td><td>499.98</td>")))));

        assertThat(text).contains("499.98").doesNotContain("See the HTML version");
    }

    @Test
    @DisplayName("table rows stay rows and cells stay apart, so each amount keeps its description")
    void tablesKeepTheirStructure() {
        String text = GmailClientService.htmlToText(
            "<style>td{color:red}</style><table><tr><td>07-Aug</td><td>SIP</td><td>&#8377;499.98</td></tr>"
                + "<tr><td>08-Aug</td><td>Refund</td><td>&#8377;120</td></tr></table>");

        assertThat(text).doesNotContain("color:red");
        assertThat(text.lines().toList()).contains("07-Aug | SIP | ₹499.98", "08-Aug | Refund | ₹120");
    }
}
