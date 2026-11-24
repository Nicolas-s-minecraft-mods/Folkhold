package io.github.nicolasgoyon.folkhold.world.faction;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPatterns;

/**
 * The cultures of the world mod: Java definitions (see {@code docs/design.md}, section "Cultures").
 */
public final class Cultures {
    /** Blue, spruce and stone. Northern names. */
    public static final Culture VALEHOLM = new Culture("valeholm", 0x3C5A9A, DyeColor.BLUE,
            List.of(new Culture.BannerLayer(BannerPatterns.STRIPE_CENTER, DyeColor.WHITE),
                    new Culture.BannerLayer(BannerPatterns.BORDER, DyeColor.LIGHT_BLUE)),
            List.of("val", "hal", "brim", "ost", "ald", "thor", "sig", "eid", "frey", "ulf", "bjor", "skar", "rav",
                    "kol", "ask", "stav", "tor", "ey", "lund", "gun", "har", "vind", "ulv", "sten", "bran", "trond",
                    "nor", "grun", "varg", "hrod", "sval", "ing", "alf", "yng", "rim", "fal", "hel", "jor", "knut",
                    "leif", "ran", "sjo", "stor", "tyr", "ved", "osk", "hag", "ulm"),
            List.of("holm", "gard", "mere", "wick", "stead", "vik", "by", "heim", "fjord", "strand", "nes", "havn",
                    "dal", "berg", "vang", "sund", "tun", "stad", "borg", "ness", "skog", "voll", "fors", "rud", "set",
                    "hus", "bo", "tofte", "vald", "lev", "rup", "sted", "holt", "bru", "aker", "hamn", "vag", "lid",
                    "myr", "sand", "hof", "vatn", "kvist", "haug", "lyng", "hed", "fjell", "stok"), 1.0F);

    /** Red, dark wood. Harsh names, low voices. */
    public static final Culture ASHMARK = new Culture("ashmark", 0x9A3C3C, DyeColor.RED,
            List.of(new Culture.BannerLayer(BannerPatterns.CROSS, DyeColor.BLACK),
                    new Culture.BannerLayer(BannerPatterns.STRIPE_BOTTOM, DyeColor.BLACK)),
            List.of("ash", "kar", "dren", "mor", "vel", "grim", "drak", "krag", "varn", "zar", "gor", "thrak", "bal",
                    "mal", "skor", "ruk", "gar", "vor", "dusk", "char", "kor", "brak", "ner", "raz", "ulk", "dor",
                    "sarn", "krel", "mog", "harr", "bael", "crom", "drog", "fen", "gul", "hask", "jarn", "murk", "nok",
                    "rot", "scath", "tarn", "urg", "vex", "wrag", "zul", "dag", "cinder", "sorn"),
            List.of("mark", "haven", "fell", "crag", "moor", "hold", "maw", "rock", "scar", "fang", "pit", "thorn",
                    "grave", "keep", "wall", "deep", "burn", "rath", "gulch", "rax", "mur", "gash", "rend", "hollow",
                    "barrow", "cairn", "shade", "spur", "vault", "drift", "brand", "forge", "ridge", "gorge", "pike",
                    "stone", "hulk", "mire", "tusk", "tooth", "claw", "coil", "brack", "husk", "gloom", "reave",
                    "wold", "karn"), 0.9F);

    /** Gold, sandstone. Bright names, high voices. */
    public static final Culture SUNREACH = new Culture("sunreach", 0xC8A030, DyeColor.YELLOW,
            List.of(new Culture.BannerLayer(BannerPatterns.CIRCLE_MIDDLE, DyeColor.ORANGE),
                    new Culture.BannerLayer(BannerPatterns.STRIPE_TOP, DyeColor.WHITE)),
            List.of("sol", "ar", "lum", "cal", "ser", "eli", "ama", "ori", "sela", "vira", "aur", "cel", "ilia",
                    "mira", "lior", "sora", "tal", "eva", "ondi", "riva", "liva", "zeph", "rani", "aelo", "cyra",
                    "belo", "ista", "nara", "quel", "ael", "bri", "cori", "dela", "esta", "fio", "gala", "ira", "jol",
                    "lua", "myra", "nel", "ope", "pela", "rhea", "tia", "uli", "vela", "yla", "zora"),
            List.of("reach", "ford", "vale", "dale", "spire", "mont", "ria", "bel", "ane", "ora", "lis", "mar", "essa",
                    "ion", "ival", "aris", "enne", "ais", "eon", "ilis", "oria", "elle", "anth", "court", "crest",
                    "shore", "bright", "bloom", "glade", "light", "sun", "lane", "mead", "brook", "field", "crown",
                    "ray", "dawn", "gleam", "lea", "summer", "well", "garden", "rise", "view", "hall", "sea",
                    "aire"), 1.1F);

    public static final List<Culture> ALL = List.of(VALEHOLM, ASHMARK, SUNREACH);

    private Cultures() {
    }

    /**
     * Finds a culture by id.
     *
     * @param id the culture id
     * @return the culture, or empty if the id is unknown
     */
    public static Optional<Culture> byId(String id) {
        return ALL.stream().filter(culture -> culture.id().equals(id)).findFirst();
    }
}
