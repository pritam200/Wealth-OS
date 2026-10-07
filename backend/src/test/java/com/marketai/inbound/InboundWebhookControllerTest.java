package com.marketai.inbound;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InboundWebhookControllerTest {

    private final InboundService service = mock(InboundService.class);
    private final InboundWebhookController controller = new InboundWebhookController(service);

    private MockMultipartHttpServletRequest request() {
        MockMultipartHttpServletRequest r = new MockMultipartHttpServletRequest();
        r.setParameter("recipient", "import-abcdefghjkmn@in.example.com");
        r.setParameter("sender", "a@b.com");
        r.setParameter("subject", "CAS");
        r.addFile(new MockMultipartFile("attachment-1", "cas.pdf", "application/pdf", new byte[]{1, 2}));
        return r;
    }

    private void configure(String secret, String mailgunKey) {
        when(service.enabled()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "webhookSecret", secret);
        ReflectionTestUtils.setField(controller, "mailgunKey", mailgunKey);
    }

    @Test
    void failsClosedWhenNoSecretIsConfigured() throws Exception {
        configure("", "");
        controller.receive("", request());
        controller.receive("anything", request());
        verify(service, never()).receive(any(), any(), any(), any());
    }

    @Test
    void aWrongSecretReadsNothing() throws Exception {
        configure("right-secret", "");
        controller.receive("wrong-secret", request());
        verify(service, never()).receive(any(), any(), any(), any());
    }

    @Test
    void theRightSecretPassesRecipientSenderAndAttachmentsOn() throws Exception {
        configure("right-secret", "");
        controller.receive("right-secret", request());
        verify(service).receive(eq("import-abcdefghjkmn@in.example.com"), eq("a@b.com"), eq("CAS"), argThat((List<InboundService.Attachment> l) -> l.size() == 1 && "cas.pdf".equals(l.get(0).filename())));
    }

    @Test
    void whenAMailgunKeyIsSetOnlyAValidSignatureIsAccepted() throws Exception {
        configure("s", "mg-key");
        MockMultipartHttpServletRequest bad = request();
        bad.setParameter("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        bad.setParameter("token", "t");
        bad.setParameter("signature", "deadbeef");
        controller.receive("s", bad);
        verify(service, never()).receive(any(), any(), any(), any());

        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("mg-key".getBytes(), "HmacSHA256"));
        MockMultipartHttpServletRequest good = request();
        good.setParameter("timestamp", ts);
        good.setParameter("token", "t");
        good.setParameter("signature", HexFormat.of().formatHex(mac.doFinal((ts + "t").getBytes())));
        controller.receive("s", good);
        verify(service).receive(any(), any(), any(), any());
    }
}
