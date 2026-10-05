package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.AssetClass;
import com.marketai.dataplatform.domain.FinancialAsset;
import com.marketai.dataplatform.pipeline.AssetRef;
import com.marketai.dataplatform.repo.FinancialAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Identification of the asset an event concerns. Identifiers are tried strongest first — ISIN,
 * scheme code, symbol, then the name reduced to letters and digits — so one fund described by an
 * ISIN in one source and only a name in another resolves to one asset. When a later source
 * supplies an identifier the asset lacked, it is added.
 */
@Service
@RequiredArgsConstructor
public class AssetResolver {

    private final FinancialAssetRepository assets;

    public FinancialAsset resolve(AssetRef ref) {
        AssetClass cls = ref.assetClass() == null ? AssetClass.OTHER : ref.assetClass();
        String isin = clean(ref.isin());
        String symbol = ref.symbol() == null || ref.symbol().isBlank() ? null : ref.symbol().trim().toUpperCase();
        String nameKey = nameKey(ref.name());
        String amfi = ref.amfiCode() == null || ref.amfiCode().isBlank() ? null : ref.amfiCode().trim();

        Optional<FinancialAsset> found = Optional.empty();
        if (isin != null) found = assets.findFirstByIsin(isin);
        if (found.isEmpty() && amfi != null) found = assets.findFirstByAmfiCode(amfi);
        if (found.isEmpty() && symbol != null) found = assets.findFirstByAssetClassAndAssetKey(cls, symbol);
        if (found.isEmpty() && nameKey != null) found = assets.findFirstByAssetClassAndNameKey(cls, nameKey);

        if (found.isPresent()) {
            FinancialAsset a = found.get();
            boolean changed = false;
            if (a.getIsin() == null && isin != null) { a.setIsin(isin); changed = true; }
            if (a.getAmfiCode() == null && amfi != null) { a.setAmfiCode(amfi); changed = true; }
            if (a.getSymbol() == null && symbol != null) { a.setSymbol(symbol); changed = true; }
            if (a.getNameKey() == null && nameKey != null) { a.setNameKey(nameKey); changed = true; }
            if ((a.getName() == null || a.getName().isBlank()) && ref.name() != null) { a.setName(ref.name().trim()); changed = true; }
            return changed ? assets.save(a) : a;
        }
        String key = isin != null ? isin : symbol != null ? symbol : nameKey != null ? "N:" + nameKey : amfi;
        return assets.save(FinancialAsset.builder().assetClass(cls).assetKey(key).symbol(symbol).isin(isin)
            .name(ref.name() == null ? null : ref.name().trim()).nameKey(nameKey).amfiCode(amfi).build());
    }

    static String clean(String s) { return s == null || s.isBlank() ? null : s.trim().toUpperCase(); }

    /** "HDFC Flexi Cap Fund - Growth (Direct)" and "hdfc flexicap fund growth direct" are not the same key; only spacing and punctuation are ignored. */
    static String nameKey(String name) {
        if (name == null) return null;
        String k = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        return k.isEmpty() ? null : k.length() > 160 ? k.substring(0, 160) : k;
    }
}
