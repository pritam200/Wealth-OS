package com.marketai.inbound;

import com.marketai.cas.CasImportService;
import com.marketai.gmail.security.PasswordCipher;
import com.marketai.onboarding.OnboardingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InboundServiceTest {

    private static final long USER = 5L;
    private static final byte[] PDF = "%PDF-fake".getBytes();

    private InboundAddressRepository addresses;
    private InboundItemRepository items;
    private OnboardingService onboarding;
    private CasImportService casImport;
    private PasswordCipher cipher;
    private InboundService service;

    @BeforeEach
    void setUp() {
        addresses = mock(InboundAddressRepository.class);
        items = mock(InboundItemRepository.class);
        onboarding = mock(OnboardingService.class);
        casImport = mock(CasImportService.class);
        cipher = mock(PasswordCipher.class);
        service = new InboundService(addresses, items, onboarding, casImport, cipher);
        ReflectionTestUtils.setField(service, "domain", "in.example.com");
        InboundAddress a = InboundAddress.builder().userId(USER).code("abcdefghjkmn").build();
        when(addresses.findByCode("abcdefghjkmn")).thenReturn(Optional.of(a));
        when(addresses.findByUserId(USER)).thenReturn(Optional.of(a));
        when(items.save(any(InboundItem.class))).thenAnswer(i -> i.getArgument(0));
    }

    private CasImportService.Summary summary() {
        return new CasImportService.Summary(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31), 2, 4, 4, 0, 0,
            List.of(), List.of(), CasImportService.Kind.MUTUAL_FUND);
    }

    private InboundItem savedItem() {
        ArgumentCaptor<InboundItem> c = ArgumentCaptor.forClass(InboundItem.class);
        verify(items).save(c.capture());
        return c.getValue();
    }

    private void deliver(String to) {
        service.receive(to, "me@x.com", "CAS", List.of(new InboundService.Attachment("cas.pdf", PDF)));
    }

    @Test
    void importsAForwardedCasForTheOwnerOfTheAddress() {
        when(casImport.needsPassword(PDF)).thenReturn(false);
        when(onboarding.importForwardedCas(eq(USER), eq(PDF), any())).thenReturn(summary());
        deliver("Import <import-abcdefghjkmn@in.example.com>");
        InboundItem i = savedItem();
        assertThat(i.getStatus()).isEqualTo(InboundItem.Status.IMPORTED);
        assertThat(i.getPdfBytes()).isNull();
    }

    @Test
    void anUnknownCodeOrAForeignDomainImportsNothing() {
        deliver("import-zzzzzzzzzzzz@in.example.com");
        deliver("import-abcdefghjkmn@evil.example.org");
        verifyNoInteractions(onboarding);
        verify(items, never()).save(any());
    }

    @Test
    void aLockedPdfWithNoSavedPasswordWaitsAndIsNotImported() {
        when(casImport.needsPassword(PDF)).thenReturn(true);
        deliver("import-abcdefghjkmn@in.example.com");
        InboundItem i = savedItem();
        assertThat(i.getStatus()).isEqualTo(InboundItem.Status.NEEDS_PASSWORD);
        assertThat(i.getPdfBytes()).isEqualTo(PDF);
        verify(onboarding, never()).importForwardedCas(any(), any(), any());
    }

    @Test
    void aSavedPasswordOpensLockedPdfsUnattended() {
        InboundAddress withPw = InboundAddress.builder().userId(USER).code("abcdefghjkmn").casPasswordEncrypted("enc").build();
        when(addresses.findByUserId(USER)).thenReturn(Optional.of(withPw));
        when(cipher.decrypt("enc")).thenReturn("ABCDE1234F");
        when(casImport.needsPassword(PDF)).thenReturn(true);
        when(onboarding.importForwardedCas(USER, PDF, "ABCDE1234F")).thenReturn(summary());
        deliver("import-abcdefghjkmn@in.example.com");
        assertThat(savedItem().getStatus()).isEqualTo(InboundItem.Status.IMPORTED);
    }

    @Test
    void aWrongSavedPasswordParksTheFileInsteadOfFailingIt() {
        InboundAddress withPw = InboundAddress.builder().userId(USER).code("abcdefghjkmn").casPasswordEncrypted("enc").build();
        when(addresses.findByUserId(USER)).thenReturn(Optional.of(withPw));
        when(cipher.decrypt("enc")).thenReturn("OLD");
        when(casImport.needsPassword(PDF)).thenReturn(true);
        when(onboarding.importForwardedCas(USER, PDF, "OLD"))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "That password didn't open the PDF. Check it and try again."));
        deliver("import-abcdefghjkmn@in.example.com");
        InboundItem i = savedItem();
        assertThat(i.getStatus()).isEqualTo(InboundItem.Status.NEEDS_PASSWORD);
        assertThat(i.getPdfBytes()).isEqualTo(PDF);
    }

    @Test
    void anAlreadyImportedAttachmentIsSkipped() {
        when(items.existsByUserIdAndSha256AndStatus(eq(USER), any(), eq(InboundItem.Status.IMPORTED))).thenReturn(true);
        deliver("import-abcdefghjkmn@in.example.com");
        verifyNoInteractions(onboarding);
        verify(items, never()).save(any());
    }

    @Test
    void nonPdfAttachmentsAreNotImportedAndSignatureImagesAreSilent() {
        service.receive("import-abcdefghjkmn@in.example.com", "me@x.com", "s", List.of(
            new InboundService.Attachment("logo.png", new byte[]{1}), new InboundService.Attachment("stmt.csv", new byte[]{1})));
        InboundItem i = savedItem(); // only the csv is recorded
        assertThat(i.getStatus()).isEqualTo(InboundItem.Status.IGNORED);
        verifyNoInteractions(onboarding);
    }

    @Test
    void nothingIsAcceptedWhenNoDomainIsConfigured() {
        ReflectionTestUtils.setField(service, "domain", "");
        deliver("import-abcdefghjkmn@in.example.com");
        verifyNoInteractions(onboarding, items);
    }
}
