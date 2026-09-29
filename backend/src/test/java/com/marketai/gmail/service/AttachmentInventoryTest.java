package com.marketai.gmail.service;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.marketai.gmail.service.GmailClientService.AttachmentKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every attachment is inventoried with how it is handled — none is passed over unrecorded. */
class AttachmentInventoryTest {

    private final GmailClientService client = new GmailClientService();

    private static MessagePart part(String mime, String filename, String attachmentId, Integer size) {
        return new MessagePart().setMimeType(mime).setFilename(filename)
            .setBody(new MessagePartBody().setAttachmentId(attachmentId).setSize(size));
    }

    @Test
    @DisplayName("PDFs, text files, spreadsheets, invites and logos are each classified")
    void classifiesEveryAttachment() {
        MessagePart root = new MessagePart().setMimeType("multipart/mixed").setParts(List.of(
            new MessagePart().setMimeType("text/html").setBody(new MessagePartBody().setData("PGI+aGk8L2I+")),
            part("application/pdf", "statement.pdf", "a1", 90_000),
            part("text/csv", "transactions.csv", "a2", 4_000),
            part("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "trades.xlsx", "a3", 20_000),
            part("text/calendar", "invite.ics", "a4", 900),
            part("image/png", "logo.png", "a5", 2_000),
            part("image/jpeg", "receipt.jpg", "a6", 400_000)));

        List<GmailClientService.AttachmentInfo> out = client.listAttachments(new Message().setPayload(root));

        assertThat(out).extracting(GmailClientService.AttachmentInfo::filename, GmailClientService.AttachmentInfo::kind)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("statement.pdf", AttachmentKind.DOCUMENT),
                org.assertj.core.groups.Tuple.tuple("transactions.csv", AttachmentKind.TEXT),
                org.assertj.core.groups.Tuple.tuple("trades.xlsx", AttachmentKind.UNSUPPORTED),
                org.assertj.core.groups.Tuple.tuple("invite.ics", AttachmentKind.NOT_A_DOCUMENT),
                org.assertj.core.groups.Tuple.tuple("logo.png", AttachmentKind.NOT_A_DOCUMENT),
                org.assertj.core.groups.Tuple.tuple("receipt.jpg", AttachmentKind.DOCUMENT));
        assertThat(out.get(2).label()).isEqualTo("trades.xlsx (spreadsheet)");
    }

    @Test
    @DisplayName("an HTML attachment is read with its table rows kept; a BOM is dropped")
    void attachmentText() {
        String html = "<table><tr><td>10-01-2026</td><td>Swiggy</td><td>1,200.00</td></tr></table>";
        assertThat(GmailClientService.attachmentText(html.getBytes(StandardCharsets.UTF_8), "s.html", "text/html"))
            .isEqualTo("10-01-2026 | Swiggy | 1,200.00");
        assertThat(GmailClientService.attachmentText("﻿date,amount".getBytes(StandardCharsets.UTF_8), "s.csv", "text/csv"))
            .isEqualTo("date,amount");
    }

    @Test
    @DisplayName("within one email, a clearly different payee is not the same line; an unknown one never proves different")
    void differentParty() {
        assertThat(ParsedEmailImporter.differentParty("Swiggy", "Zomato")).isTrue();
        assertThat(ParsedEmailImporter.differentParty("SWIGGY*BLR", "Swiggy")).isFalse();
        assertThat(ParsedEmailImporter.differentParty(null, "Zomato")).isFalse();
    }
}
