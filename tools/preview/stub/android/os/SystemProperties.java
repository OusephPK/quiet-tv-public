package android.os;
public class SystemProperties { public static String get(String k){ return System.getProperty(k, ""); } }
