package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.AssetClass;

/** How a source names an asset. Any identifier may be missing; the resolver uses what is present. */
public record AssetRef(AssetClass assetClass, String symbol, String isin, String name, String amfiCode) {
    public boolean identified() {
        return notBlank(isin) || notBlank(symbol) || notBlank(name) || notBlank(amfiCode);
    }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
