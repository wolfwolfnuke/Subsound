package org.subsound.utils.javahttp;

import org.junit.Test;

import static org.subsound.utils.javahttp.TextUtils.parseLink;
import static org.assertj.core.api.Assertions.assertThat;

public class TextUtilsTest {

    @Test
    public void testescapeMarkupGtkLabel() {
        assertThat(TextUtils.escapeMarkupGtkLabel("foo")).isEqualTo("foo");
        assertThat(TextUtils.escapeMarkupGtkLabel("foo <bar>")).isEqualTo("foo &lt;bar&gt;");
        assertThat(TextUtils.escapeMarkupGtkLabel("foo <bar> baz")).isEqualTo("foo &lt;bar&gt; baz");
        assertThat(TextUtils.escapeMarkupGtkLabel("foo <bar> baz <quux>")).isEqualTo("foo &lt;bar&gt; baz &lt;quux&gt;");
        assertThat(TextUtils.escapeMarkupGtkLabel("King Geedorah featuring Mr Fantastik & Hassan Chop & DOOM"))
                .isEqualTo("King Geedorah featuring Mr Fantastik &amp; Hassan Chop &amp; DOOM");
    }

    @Test
    public void testBiography() {
        assertThat(parseLink("")).isNotNull();
        String sample = "backing vocals <a target='_blank' href=\"https://www.last.fm/music/The+Test+Band\" rel=\"nofollow\">Read more on Last.fm</a>";
        assertThat(parseLink(sample)).satisfies(bio -> {
            assertThat(bio.original()).isEqualTo(sample);
            assertThat(bio.cleaned()).isEqualTo("backing vocals");
            assertThat(bio.link()).isEqualTo("<a  href=\"https://www.last.fm/music/The+Test+Band\" >Read more on Last.fm</a>");
        });
    }

}