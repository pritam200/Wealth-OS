package com.marketai.dataplatform.domain;

/**
 * What kind of data a provider delivers. The UI must never show a MOCK or TEST provider as a
 * live connection to a financial institution.
 */
public enum ProviderMode { MOCK, TEST, LIVE }
