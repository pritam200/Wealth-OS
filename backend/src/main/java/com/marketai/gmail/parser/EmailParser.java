package com.marketai.gmail.parser;

import java.util.List;

public interface EmailParser {
    boolean canParse(String from, String subject);
    List<ParsedEmail> parse(String from, String subject, String bodyText);
}
