package dev.kodex.ext.madara;

import org.pf4j.Extension;

@Extension
public class ManhuaFastSource extends MadaraSource {

    public ManhuaFastSource() {
        super("ManhuaFast", "https://manhuafast.com", "en", false);
    }
}
