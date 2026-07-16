package dev.kodex.plugin.hentaifox;

import org.pf4j.Extension;

/** HentaiFox — Japanese galleries. */
@Extension
public class HentaiFoxJa extends HentaiFoxSource {

    @Override
    protected String mangaLang() {
        return "japanese";
    }

    @Override
    public String language() {
        return "ja";
    }
}
