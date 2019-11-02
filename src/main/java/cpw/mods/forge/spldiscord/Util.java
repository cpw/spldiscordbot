package cpw.mods.forge.spldiscord;

import discord4j.core.object.util.Snowflake;

public class Util {
    public static Snowflake env(String name) {
        return Snowflake.of(System.getenv().getOrDefault(name, "0"));
    }

    public static String defaultEnv(String name, String def) {
        return System.getenv().getOrDefault(name, def);
    }
}
