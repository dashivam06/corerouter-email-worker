package com.fleebug.utility;


import io.github.cdimascio.dotenv.Dotenv;

public class Env {
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    // Private constructor prevents instantiation
    private Env() {} 

    public static String get(String key) {
        String value = System.getenv(key);
        if (value == null) value = dotenv.get(key);
        return value;
    }
    
    public static int getInt(String key, int defaultValue) {
        String value = get(key);
        return (value != null) ? Integer.parseInt(value) : defaultValue;
    }
}
