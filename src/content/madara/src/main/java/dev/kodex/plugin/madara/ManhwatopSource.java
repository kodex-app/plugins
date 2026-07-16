package dev.kodex.plugin.madara;

import org.pf4j.Extension;

@Extension
public class ManhwatopSource extends MadaraSource {

    public ManhwatopSource() {
        super("Manhwatop", "https://manhwatop.com", "en", false);
    }
}
