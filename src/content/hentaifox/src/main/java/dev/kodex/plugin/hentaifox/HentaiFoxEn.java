package dev.kodex.plugin.hentaifox;

import org.pf4j.Extension;

/** HentaiFox — English galleries. */
@Extension
public class HentaiFoxEn extends HentaiFoxSource {

    @Override
    protected String mangaLang() {
        return "english";
    }

    @Override
    public String language() {
        return "en";
    }
}
