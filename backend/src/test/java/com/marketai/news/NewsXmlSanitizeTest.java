package com.marketai.news;

import com.marketai.news.service.NewsService;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

class NewsXmlSanitizeTest {
    @Test
    void bareAmpersandsAreEscapedAndCdataAndRealEntitiesUntouched() throws Exception {
        String raw = "<rss><channel><item><title>S&P gains &amp; Nifty</title><link>http://x/?a=1&D=2</link>"
            + "<description><![CDATA[Tom & Jerry &D]]></description></item></channel></rss>";
        String fixed = NewsService.escapeBareAmpersands(raw);
        assertThat(fixed).contains("S&amp;P gains &amp; Nifty").contains("a=1&amp;D=2").contains("<![CDATA[Tom & Jerry &D]]>");
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(fixed)));
        assertThat(doc.getElementsByTagName("title").item(0).getTextContent()).isEqualTo("S&P gains & Nifty");
    }
}
